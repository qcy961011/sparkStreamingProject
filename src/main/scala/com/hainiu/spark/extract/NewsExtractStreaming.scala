package com.hainiu.spark.extract

import java.io.BufferedReader
import java.net.URL
import java.util.Date
import java.{lang, util}

import com.hainiu.spark.broadcast.{BroadcastWapper, MapAccumulator}
import com.hainiu.spark.config.MyConfig
import com.hainiu.spark.db.JedisConnectionPool
import com.hainiu.spark.utils._
import com.hainiu.spark.utils.extractor.HtmlContentExtractor
import kafka.utils.{ZKGroupTopicDirs, ZkUtils}
import org.I0Itec.zkclient.ZkClient
import org.apache.commons.codec.digest.DigestUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.apache.hadoop.hbase.client.{Connection, ConnectionFactory, HTable, Put}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010._
import org.apache.spark.streaming.{Durations, StreamingContext}
import org.elasticsearch.spark._
import org.jsoup.Jsoup
import redis.clients.jedis.{Jedis, Transaction}

import scala.collection.convert.wrapAsJava.mutableSeqAsJavaList
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, Map}
import scala.util.control.Breaks._

/**
  * 偏移量保存到zk中
  * 不使用DStream的transform等其它算子
  * 将DStream数据处理方式转成纯正的spark-core的数据处理方式
  * 由于SparkStreaming程序长时间中断，再次消费时kafka中数据已过时，
  * 上次记录消费的offset已丢失的问题处理
  */
class NewsExtractStreaming

object NewsExtractStreaming {


  def main(args: Array[String]): Unit = {
    //指定组名
    val group = MyConfig.KAFKA_GROUP
    //创建SparkConf
    val conf = new SparkConf().setAppName("newsextractstreaming")
    //    conf.setMaster("local[49]")
    //    conf.set("es.index.auto.create", "true")      //使用ES的java api手动创建索引
    conf.set("es.nodes", MyConfig.ES_HOST)
    conf.set("es.port", MyConfig.ES_PORT)

    //创建SparkStreaming，设置间隔时间
    val ssc: StreamingContext = new StreamingContext(conf, Durations.seconds(5))

    //    ssc.sparkContext.setLogLevel("INFO")

    //指定 topic 名字
    val topic = MyConfig.KAFKA_TOPIC
    //指定kafka的broker地址，SparkStream的Task直连到kafka的分区上，用底层的API消费，效率更高
    val brokerList = MyConfig.KAFKA_BROKER
    //指定zk的地址，更新消费的偏移量时使用，当然也可以使用Redis和MySQL来记录偏移量
    val zkQuorum = MyConfig.ZOOKEEPER
    //SparkStreaming时使用的topic集合，可同时消费多个topic
    val topics: Set[String] = Set(topic)
    //topic在zk里的数据路径，用于保存偏移量
    val topicDirs = new ZKGroupTopicDirs(group, topic)
    //得到zk中的数据路径 例如："/consumers/${group}/offsets/${topic}"
    val zkTopicPath = s"${topicDirs.consumerOffsetDir}"

    //kafka参数
    val kafkaParams = Map(
      "bootstrap.servers" -> brokerList,
      "group.id" -> group,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "enable.auto.commit" -> (false: lang.Boolean),
      //earliest  当各分区下有已提交的offset时，从提交的offset开始消费；无提交的offset时，从头开始消费
      //latest  当各分区下有已提交的offset时，从提交的offset开始消费；无提交的offset时，消费新产生的该分区下的数据
      //none  topic各分区都存在已提交的offset时，从offset后开始消费；只要有一个分区不存在已提交的offset，则抛出异常
      "auto.offset.reset" -> MyConfig.KAFKA_OFFSET_POSITION
    )

    //定义一个空的kafkaStream，之后根据是否有历史的偏移量进行选择
    var kafkaStream: InputDStream[ConsumerRecord[String, String]] = null

    //创建zk客户端，可以从zk中读取偏移量数据，并更新偏移量
    val zkClient = new ZkClient(zkQuorum)


    //**********用于解决SparkStreaming程序长时间中断，再次消费时已记录的offset丢失导致程序启动报错问题

    //判断ZK中是否存在历史的offset
    val zkExist: Boolean = zkClient.exists(s"$zkTopicPath")
    if (zkExist) {
      val clusterEarliestOffsets: Map[Long, Long] = KafkaUtil.getPartitionOffset(topic)
      val nowOffsetMap: HashMap[TopicPartition, Long] = KafkaUtil.getPartitionOffsetZK(topic, zkTopicPath, clusterEarliestOffsets, zkClient)
      //通过KafkaUtils创建直连的DStream，并使用fromOffsets中存储的历史偏离量来继续消费数据
      kafkaStream = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe(topics, kafkaParams, nowOffsetMap))
    } else {
      //如果zk中没有该topic的历史offset，那就根据kafkaParam的配置使用最新(latest)或者最旧的(earliest)的offset
      kafkaStream = KafkaUtils.createDirectStream(ssc, PreferConsistent, Subscribe(topics, kafkaParams))
    }


    // 创建5个累加器, 用来记录每个批次的抽取情况
    //    val scanAccumulator: LongAccumulator = ssc.sparkContext.longAccumulator
    //    val filteredAccumulator: LongAccumulator = ssc.sparkContext.longAccumulator
    //    val extractAccumulator: LongAccumulator = ssc.sparkContext.longAccumulator
    //    val emptyAccumulator: LongAccumulator = ssc.sparkContext.longAccumulator
    //    val noMatchAccumulator: LongAccumulator = ssc.sparkContext.longAccumulator

    //创建5个自定义累加器, 用来记录每个批次中每个host的抽取情况
    val hostScanAccumulator = new MapAccumulator
    val hostFilteredAccumulator = new MapAccumulator
    val hostExtractAccumulator = new MapAccumulator
    val hostEmptyAccumulator = new MapAccumulator
    val hostNoMatchAccumulator = new MapAccumulator
    ssc.sparkContext.register(hostScanAccumulator)
    ssc.sparkContext.register(hostFilteredAccumulator)
    ssc.sparkContext.register(hostExtractAccumulator)
    ssc.sparkContext.register(hostEmptyAccumulator)
    ssc.sparkContext.register(hostNoMatchAccumulator)

    // 将xpath配置做成广播变量, 定时进行广播变量的更新
    val xpathInfo = new BroadcastWapper[HashMap[String, HashMap[String, HashSet[String]]]](ssc, new HashMap[String, HashMap[String, HashSet[String]]]())
    //通过rdd转换得到偏移量的范围
    var offsetRanges = Array[OffsetRange]()

    // 记录xpath配置上次更新的时间
    // 这个可以是本地变量的形式, 因为这个变量只在driver上使用到了
    var lastUpdateTime = 0L

    //迭代DStream中的RDD，将每一个时间间隔对应的RDD拿出来，这个方法是在driver端执行
    //在foreachRDD方法中就跟开发spark-core是同样的流程了，当然也可以使用spark-sql
    kafkaStream.foreachRDD(kafkaRDD => {

      if (!kafkaRDD.isEmpty()) {
        //得到该RDD对应kafka消息的offset,该RDD是一个KafkaRDD，所以可以获得偏移量的范围
        //不使用transform可以直接在foreachRDD中得到这个RDD的偏移量，这种方法适用于DStream不经过任何的转换，
        //直接进行foreachRDD，因为如果transformation了那就不是KafkaRDD了，就不能强转成HasOffsetRanges了，从而就得不到kafka的偏移量了
        offsetRanges = kafkaRDD.asInstanceOf[HasOffsetRanges].offsetRanges
        //先进行广播变量的更新操作,
        //foreachRDD是在driver上执行的, 所以不需要耗费每个worker的执行时间
        //把xpathInfo.value.isEmpty放后面可以减少其被执行的次数
        if (System.currentTimeMillis() - lastUpdateTime > MyConfig.UPDATE_XPATH_INTERVAL || xpathInfo.value.isEmpty) {
          // 存储xpath配置的数据结构
          val xpathInfoUpdate: HashMap[String, HashMap[String, HashSet[String]]] = new HashMap[String, HashMap[String, HashSet[String]]]()

          val fs = FileSystem.get(new Configuration())
          val fileStatuses: Array[FileStatus] = fs.listStatus(new Path(MyConfig.XPATH_INFO_DIR))

          // 读取配置文件, 将xpath信息存入到xpathInfoUpdate


          for (fileStatus <- fileStatuses) {
            breakable {
              val filePath = fileStatus.getPath
              if (filePath.toString.endsWith("_COPYING_")) {
                break()
              }

              val reader: BufferedReader = FileUtil.getBufferedReader(filePath)

              var line: String = reader.readLine()
              while (line != null) {
                val strings = line.split("\t")

                // 避免出现数组越界
                if (strings.length >= 3) {
                  val host = strings(0)
                  val xpath = strings(1)
                  val xpathType = strings(2)

                  val hostXpathInfo = xpathInfoUpdate.getOrElseUpdate(host, new HashMap[String, HashSet[String]]())
                  //上面这行代码与下面两行代码作用相同
                  //val hostXpathInfo = xpathInfoUpdate.getOrElse(host, new HashMap[String, HashSet[String]]())
                  //xpathInfoUpdate += host ->  new HashMap[String, HashSet[String]]()

                  val hostTpyeXpathInfo = hostXpathInfo.getOrElseUpdate(xpathType, new HashSet[String]())
                  hostTpyeXpathInfo.add(xpath)
                }

                line = reader.readLine()
              }
              reader.close()
            }
          }

          // 更新xpath配置的广播变量
          xpathInfo.update(xpathInfoUpdate, blocking = true)
          // 更新xpath配置更新的时间广播变量
          lastUpdateTime = System.currentTimeMillis()
        }


        val dataRDD: RDD[(String, String, String, String, String, String)] = kafkaRDD.filter(cr => {
          val record: String = cr.value()
          val strings: Array[String] = record.split("\001")
          var isCorrect: Boolean = true
          //          scanAccumulator.add(1L)

          if (strings.length == 3) {
            val md5 = strings(0)
            val url = strings(1)
            val html = strings(2)
            val urlT = new URL(url)
            val host: String = urlT.getHost
            hostScanAccumulator.add(host -> 1L)
            val checkMd5 = DigestUtils.md5Hex(s"$url\001$html")
            if (!checkMd5.equals(md5)) {
              hostFilteredAccumulator.add(host -> 1L)
              isCorrect = false
            }
          } else if (strings.length == 2) {
            val url = strings(1)
            val urlT = new URL(url)
            val host: String = urlT.getHost
            hostScanAccumulator.add(host -> 1L)
            hostFilteredAccumulator.add(host -> 1L)
            isCorrect = false
          } else {
            //            filteredAccumulator.add(1)
            hostScanAccumulator.add("HOST_NULL" -> 1L)
            hostFilteredAccumulator.add("HOST_NULL" -> 1L)
            isCorrect = false
          }
          isCorrect
        }).mapPartitions(iter => {

          // 从广播变量中得到xpath信息
          val xpathMap: HashMap[String, HashMap[String, HashSet[String]]] = xpathInfo.value


          // 缓存hbase记录
          val puts = new ListBuffer[Put]
          // 缓存ES记录，用于返回
          val list = new ListBuffer[(String, String, String, String, String, String)]

          iter.foreach(cr => {
            val strings = cr.value().split("\001")
            val url: String = strings(1)
            val urlT = new URL(url)
            val host: String = urlT.getHost
            val domain: String = JavaUtil.getDomainName(urlT)
            val html: String = strings(2)

            val xpathMapT: util.Map[String, String] = HtmlContentExtractor.generateXpath(html)
            val faileRule = new ArrayBuffer[String]
            var trueRule = ""
            if (JavaUtil.isNotEnpty(xpathMapT)) {
              val value: util.Iterator[util.Map.Entry[String, String]] = xpathMapT.entrySet().iterator()
              while (value.hasNext) {
                val entry: util.Map.Entry[String, String] = value.next()
                val key = entry.getKey
                if (key != null && !key.trim().equals("")) {
                  if (entry.getValue.equals(HtmlContentExtractor.CONTENT)) {
                    trueRule = key
                  } else {
                    faileRule += key
                  }
                }

              }
            }

            val redis: Jedis = JedisConnectionPool.getConnection()
            //把正反规则存到redis中,使用redis事务
            if (!trueRule.equals("") || !faileRule.isEmpty) {
              val transaction: Transaction = redis.multi()
              //切换数据库
              transaction.select(6)

              //正规则存总值和有序集合，总值累加key的前缀为total开头，反规则存自动排序的集合，Key的前缀为txpath开头
              if (!trueRule.equals("")) {
                transaction.incr(s"total:${host}")
                transaction.zincrby(s"txpath:${host}", 1, trueRule)
              }

              //反规则存集合，Key的前缀为fxpath开头
              if (!faileRule.isEmpty) {
                transaction.sadd(s"fxpath:${host}", faileRule: _*)
              }
              transaction.exec()
            }
            redis.close()

            // 正文抽取，拿到host对应的正反规则
            if (xpathMap.contains(host)) {
              val hostXpathInfo: HashMap[String, HashSet[String]] = xpathMap(host)
              val hostPositiveXpathInfo: HashSet[String] = hostXpathInfo.getOrElse("true", new HashSet[String])
              val hostNegativeXpathInfo: HashSet[String] = hostXpathInfo.getOrElse("false", new HashSet[String])

              // 抽取正文
              val doc = Jsoup.parse(html)

              //直接使用java代码, 这里需要导入java和scala集合的隐式转换
              //               import scala.collection.JavaConversions._
              //               val content: String = JavaUtil.getcontext(doc, hostPositiveXpathInfo.toList, hostNegativeXpathInfo.toList)

              // 使用scala代码
              val content = Util.getContext(doc, hostPositiveXpathInfo, hostNegativeXpathInfo)

              if (content.trim.length >= 10) {
                val time = new Date().getTime
                val urlMd5: String = DigestUtils.md5Hex(url)
                list += ((url, host, content, html, domain, urlMd5))

                // hbase
                //create 'context_extract',{NAME => 'i', VERSIONS => 1, BLOCKCACHE => true,COMPRESSION => 'SNAPPY'},
                // {NAME => 'c', VERSIONS => 1, BLOCKCACHE => true,COMPRESSION => 'SNAPPY'},
                // {NAME => 'h', VERSIONS => 1,COMPRESSION => 'SNAPPY'}
                val put = new Put(Bytes.toBytes(s"${host}_${Util.getTime(time, "yyyyMMddHHmmssSSSS")}_$urlMd5"))
                if (JavaUtil.isNotEnpty(url)) put.addColumn(Bytes.toBytes("i"), Bytes.toBytes("url"), Bytes.toBytes(url))
                if (JavaUtil.isNotEnpty(domain)) put.addColumn(Bytes.toBytes("i"), Bytes.toBytes("domain"), Bytes.toBytes(domain))
                if (JavaUtil.isNotEnpty(host)) put.addColumn(Bytes.toBytes("i"), Bytes.toBytes("host"), Bytes.toBytes(host))
                if (JavaUtil.isNotEnpty(content)) put.addColumn(Bytes.toBytes("c"), Bytes.toBytes("context"), Bytes.toBytes(content))
                if (JavaUtil.isNotEnpty(html)) put.addColumn(Bytes.toBytes("h"), Bytes.toBytes("html"), Bytes.toBytes(html))
                puts += put
                hostExtractAccumulator.add(host -> 1L)
                //                extractAccumulator.add(1L)
              } else {
                // 根据xpath匹配到的正文长度小于10, 说明这个xpath很可能不正确
                hostEmptyAccumulator.add(host -> 1L)
                //                emptyAccumulator.add(1L)
              }
            } else {
              // 此host没有匹配的xpath规则
              hostNoMatchAccumulator.add(host -> 1L)
              //              noMatchAccumulator.add(1L)
            }
          })

          //保存到hbase
          val hbaseConf: Configuration = HBaseConfiguration.create()
          val connection: Connection = ConnectionFactory.createConnection(hbaseConf)
          val table: HTable = connection.getTable(TableName.valueOf(MyConfig.HBASE_TABLE_NAME)).asInstanceOf[HTable]
          table.put(puts)
          table.close()
          connection.close()

          list.toIterator
        })

        //使用这种方式保存hbase会导致多启动一个action，就会造成累加器多累加一次
        //        val hbaseConf: Configuration = HBaseConfiguration.create()
        //        hbaseConf.set(TableOutputFormat.OUTPUT_TABLE, MyConfig.HBASE_TABLE_NAME)
        //        hbaseConf.set(MRJobConfig.OUTPUT_FORMAT_CLASS_ATTR,classOf[TableOutputFormat[ImmutableBytesWritable]].getName)
        //        hbaseConf.set("mapreduce.job.output.key.class",classOf[ImmutableBytesWritable].getName)
        //        hbaseConf.set("mapreduce.job.output.value.class",classOf[Put].getName)
        //        dataRDD.mapPartitions(it => {
        //          val time = new Date().getTime
        //          val list = new ListBuffer[(ImmutableBytesWritable, Put)]
        //          it.foreach(f => {
        //            val url: String = f._1
        //            val host: String = f._2
        //            val content: String = f._3
        //            val html: String = f._4
        //            val domain:String = f._5
        //            val urlMd5:String = f._6
        //            val put = new Put(Bytes.toBytes(s"${host}_${Util.getTime(time, "yyyyMMddHHmmss")}_$urlMd5"))
        //            if (JavaUtil.isNotEnpty(url)) put.addColumn(Bytes.toBytes("i"), Bytes.toBytes("url"), Bytes.toBytes(url))
        //            if (JavaUtil.isNotEnpty(domain)) put.addColumn(Bytes.toBytes("i"), Bytes.toBytes("domain"), Bytes.toBytes(domain))
        //            if (JavaUtil.isNotEnpty(host)) put.addColumn(Bytes.toBytes("i"), Bytes.toBytes("host"), Bytes.toBytes(host))
        //            if (JavaUtil.isNotEnpty(content)) put.addColumn(Bytes.toBytes("c"), Bytes.toBytes("context"), Bytes.toBytes(content))
        //            if (JavaUtil.isNotEnpty(html)) put.addColumn(Bytes.toBytes("h"), Bytes.toBytes("html"), Bytes.toBytes(html))
        //            val keyOut = new ImmutableBytesWritable()
        //            keyOut.set(put.getRow)
        //            list += ((keyOut, put))
        //          })
        //          list.toIterator
        //        }).saveAsNewAPIHadoopDataset(hbaseConf)

        //保存到ES
        dataRDD.mapPartitions(f => {
          val date: String = Util.getTime(new Date().getTime, "yyyy-MM-dd HH:mm:ss")
          val rl = new ListBuffer[mutable.Map[String, String]]
          for (t <- f) {
            val url: String = t._1
            val host: String = t._2
            val content: String = t._3
            val domain: String = t._5
            val urlMd5: String = t._6
            val rm = Map("url" -> url,
              "url_md5" -> urlMd5,
              "host" -> host,
              "domain" -> domain,
              "date" -> date,
              "content" -> content)
            rl += rm
          }
          rl.toIterator
        }).saveToEs(MyConfig.ES_INDEX_TYPE)

        val scanAccMap: HashMap[Any, Any] = hostScanAccumulator.value
        val scanNum = scanAccMap.foldLeft(0L)(_ + _._2.asInstanceOf[Long])
        //        val scanNum = scanAccumulator.count
        val filteredAccMap: HashMap[Any, Any] = hostFilteredAccumulator.value
        val filteredNum = filteredAccMap.foldLeft(0L)(_ + _._2.asInstanceOf[Long])
        //        val filteredNum = filteredAccumulator.count
        val extractAccMap: HashMap[Any, Any] = hostExtractAccumulator.value
        val extractNum = extractAccMap.foldLeft(0L)(_ + _._2.asInstanceOf[Long])
        //        val extractNum = extractAccumulator.count
        val emptyAccMap: HashMap[Any, Any] = hostEmptyAccumulator.value
        val emptyNum = emptyAccMap.foldLeft(0L)(_ + _._2.asInstanceOf[Long])
        //        val emptyNum = emptyAccumulator.count
        val noMatchAccMap: HashMap[Any, Any] = hostNoMatchAccumulator.value
        val noMatchNum = noMatchAccMap.foldLeft(0L)(_ + _._2.asInstanceOf[Long])
        //        val noMatchNum = noMatchAccumulator.count

        // 打印本批次的统计信息, 可以在driver上看到
        println(s"last modify time : ${Util.getCurrentTime}")
        println(s"report ===> scan: $scanNum, " +
          s"filtered: $filteredNum, " +
          s"extract: $extractNum, " +
          s"empty: $emptyNum, " +
          s"noMatchXpath: $noMatchNum")

        // 重置累加器值
        //        scanAccumulator.reset()
        //        filteredAccumulator.reset()
        //        extractAccumulator.reset()
        //        emptyAccumulator.reset()
        //        noMatchAccumulator.reset()
        hostScanAccumulator.reset()
        hostFilteredAccumulator.reset()
        hostExtractAccumulator.reset()
        hostEmptyAccumulator.reset()
        hostNoMatchAccumulator.reset()

        // 将统计数据写入到mysql中
        // insertIntoMysqlByJdbc(scanNum, filteredNum, extractNum, emptyNum, noMatchNum)
        DBUtil.insertIntoMysqlByJdbc(scanAccMap, filteredAccMap, extractAccMap, emptyAccMap, noMatchAccMap)

        //将offset存到zookeeper
        for (o <- offsetRanges) {
          //  /consumers/qingniu/offsets/hainiu_qingniu/0
          val zkPath = s"${topicDirs.consumerOffsetDir}/${o.partition}"
          //将该 partition 的 offset 保存到 zookeeper
          //  /consumers/qingniu/offsets/hainiu_qingniu/888
          ZkUtils(zkClient, false).updatePersistentPath(zkPath, o.untilOffset.toString)
        }
      }
    })

    ssc.start()
    ssc.awaitTermination()
  }

}
