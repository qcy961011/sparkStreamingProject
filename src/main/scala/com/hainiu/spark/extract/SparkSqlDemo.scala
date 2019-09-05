package com.hainiu.spark.extract

import java.io.File
import java.util
import kafka.{PropertiesScalaUtils, RedisKeysListUtils}
import kafka.SparkStreamingKafka.{dbIndex, kafkaStreams}
import net.sf.json.JSONObject
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, HasOffsetRanges, KafkaUtils, LocationStrategies}
import redis.RedisPool

object SparkSqlDemo {
  def main(args: Array[String]): Unit = {
    Logger.getLogger("org.apache.spark").setLevel(Level.INFO)
    Logger.getLogger("org.eclipse.jetty.server").setLevel(Level.INFO)
    Logger.getLogger("org.apache.kafka.clients.consumer").setLevel(Level.INFO)
    val warehouseLocation = new File("hdfs://cluster/hive/warehouse").getAbsolutePath
    val spark = SparkSession.builder().appName("Spark SQL Jason").config("spark.sql.warehouse.dir", warehouseLocation).enableHiveSupport().getOrCreate()
    spark.conf.set("spark.streaming.kafka.maxRatePerPartition", "2000")
    spark.conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    spark.conf.set("spark.streaming.concurrentJobs", "10")
    spark.conf.set("spark.streaming.kafka.maxRetries", "50")
    @transient
    val sc = spark.sparkContext
    val scc = new StreamingContext(sc, Seconds(2))
    val topic = "jason_20180511"
    val topicSet: Set[String] = Set(topic) //设置kafka的topic;
    val kafkaParams = Map[String, Object](
      "auto.offset.reset" -> "latest",
      "value.deserializer" -> classOf[StringDeserializer]
      , "key.deserializer" -> classOf[StringDeserializer]
      , "bootstrap.servers" -> PropertiesScalaUtils.loadProperties("broker")
      , "group.id" -> PropertiesScalaUtils.loadProperties("groupId")
      , "enable.auto.commit" -> (false: java.lang.Boolean)
    )
    val maxTotal = 200
    val maxIdle = 100
    val minIdle = 10
    val testOnBorrow = false
    val testOnReturn = false
    val maxWaitMillis = 500
    RedisPool.makePool(PropertiesScalaUtils.loadProperties("redisHost"), PropertiesScalaUtils.loadProperties("redisPort").toInt, maxTotal, maxIdle, minIdle, testOnBorrow, testOnReturn, maxWaitMillis)
    val jedis = RedisPool.getPool.getResource
    jedis.select(dbIndex)
    val keys: util.Set[String] = jedis.keys(topic + "*")
    if (keys.size() == 0) {
      kafkaStreams = KafkaUtils.createDirectStream[String, String](
        scc,
        LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe[String, String](topicSet, kafkaParams))
    } else {
      val fromOffsets: Map[TopicPartition, Long] = RedisKeysListUtils.getKeysList(PropertiesScalaUtils.loadProperties("redisHost"), PropertiesScalaUtils.loadProperties("redisPort").toInt, topic)
      kafkaStreams = KafkaUtils.createDirectStream[String, String](
        scc,
        LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe[String, String](topicSet, kafkaParams, fromOffsets))
    }
    RedisPool.getPool.returnResource(jedis)
    kafkaStreams.foreachRDD(rdd=>{
      val jedis_jason = RedisPool.getPool.getResource
      jedis_jason.select(dbIndex)
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
      if(!rdd.isEmpty()){
        val rowRDD:RDD[Row] = rdd.map(x=>{
          val json = JSONObject.fromObject(x.value().toString)
          val a = json.get("name")
          val b = json.get("addr")
          Row(a,b)
        })
        val schemaString = "name addr"
        val field = schemaString.split(" ").map(x=> StructField(x,StringType,nullable = true))
        val schema = StructType(field)
        val df = spark.createDataFrame(rowRDD, schema)
        df.show()
        df.createOrReplaceTempView("tempTable")
        val sq = "insert into test_2 select * from tempTable"
        sql(sq)
        println("插入hive成功了")
      }
      offsetRanges.foreach { offsetRange =>
        println("partition : " + offsetRange.partition + " fromOffset:  " + offsetRange.fromOffset + " untilOffset: " + offsetRange.untilOffset)
        val topic_partition_key_new = offsetRange.topic + "_" + offsetRange.partition
        jedis_jason.set(topic_partition_key_new, offsetRange.untilOffset + "")
      }
    })
    scc.start()
    scc.awaitTermination()
  }
}