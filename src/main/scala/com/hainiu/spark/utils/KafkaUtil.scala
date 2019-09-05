package com.hainiu.spark.utils

import kafka.api.PartitionOffsetRequestInfo
import kafka.common.TopicAndPartition
import kafka.javaapi.{OffsetRequest, PartitionMetadata, TopicMetadataRequest, TopicMetadataResponse}
import kafka.javaapi.consumer.SimpleConsumer
import org.I0Itec.zkclient.ZkClient
import org.apache.kafka.common.TopicPartition

import scala.collection.mutable.{Buffer, HashMap, Map}

object KafkaUtil {

  /**
    * 得到kafka中指定topic下每个partitioner对应的offset
    */
  def getPartitionOffset(topic: String): Map[Long, Long] = {
    val topics: Set[String] = Set(topic)
    //存储kafka集群中每个partition当前最早的offset
    var clusterEarliestOffsets = Map[Long, Long]()
    val consumer: SimpleConsumer = new SimpleConsumer("s1.hadoop", 9092, 100000, 64 * 1024,
      "leaderLookup" + System.currentTimeMillis())
    //使用隐式转换进行java和scala的类型的互相转换
    import scala.collection.convert.wrapAll._
    val request: TopicMetadataRequest = new TopicMetadataRequest(topics.toList)
    val response: TopicMetadataResponse = consumer.send(request)
    consumer.close()

    val metadatas: Buffer[PartitionMetadata] = response.topicsMetadata.flatMap(f => f.partitionsMetadata)
    //从kafka集群中得到当前每个partition最早的offset值
    metadatas.map(f => {
      val partitionId: Int = f.partitionId
      val leaderHost: String = f.leader.host
      val leaderPort: Int = f.leader.port
      val clientName: String = "Client_" + topic + "_" + partitionId
      val consumer: SimpleConsumer = new SimpleConsumer(leaderHost, leaderPort, 100000,
        64 * 1024, clientName)

      val topicAndPartition = new TopicAndPartition(topic, partitionId)
      var requestInfo = new HashMap[TopicAndPartition, PartitionOffsetRequestInfo]();
      requestInfo.put(topicAndPartition, new PartitionOffsetRequestInfo(kafka.api.OffsetRequest.EarliestTime, 1));
      val request = new OffsetRequest(requestInfo, kafka.api.OffsetRequest.CurrentVersion, clientName)
      val response = consumer.getOffsetsBefore(request)
      val offsets: Array[Long] = response.offsets(topic, partitionId)
      consumer.close()
      clusterEarliestOffsets += ((partitionId, offsets(0)))
    }
    )
    clusterEarliestOffsets
  }

  /**
    * 得到最终每个partition对应的offset
    * 从zookeeper中得到每个partition对应的offset
    */
  def getPartitionOffsetZK(topic: String, zkTopicPath: String, clusterEarliestOffsets: Map[Long, Long], zkClient: ZkClient): HashMap[TopicPartition, Long] = {
    var nowOffset = new HashMap[TopicPartition, Long]

    for ((clusterPartition, clusterEarliestOffset) <- clusterEarliestOffsets) {
      //zkTopicPath = /consumers/qingniu/offsets/hainiu_qingniu/
      //判断  /consumers/${group}/offsets/${topic}/${partitionId}/   是否存在
      val zkExist: Boolean = zkClient.exists(s"$zkTopicPath/${clusterPartition}")
      val tp = new TopicPartition(topic, clusterPartition.toInt)
      if (zkExist) {
        //从zk中查询该partition的offset，这个offset是我们自己根据每个topic的不同partition生成的
        //数据路径例子：/consumers/${group}/offsets/${topic}/${partitionId}
        val partitionOffset = zkClient.readData[String](s"$zkTopicPath/${clusterPartition}")
        // hainiu_qingniu/0

        val myOffset = partitionOffset.toLong

        //将每个partition对应的offset保存到nowOffsets中，根据zk中保存的历史偏移量与kafka集群中EarliestOffset进行对比
        // hainiu_qingniu/0 -> 888
        if (myOffset >= clusterEarliestOffset) {
          nowOffset += tp -> myOffset
        } else {
          nowOffset += tp -> clusterEarliestOffset
        }
      } else {
        nowOffset += tp -> clusterEarliestOffset
      }
    }
    nowOffset

    //使用这个方法再扩展kafka分区时，将会导致streaming不能读取新加分区的数据
    //    val children = zkClient.countChildren(zkTopicPath)
    //    for (i <- 0 until children) {
    //    //      // /consumers/qingniu/offsets/hainiu_qingniu/0
    //    //      val partitionOffset = zkClient.readData[String](s"$zkTopicPath/${i}")
    //    //      // hainiu_qingniu/0
    //    //      val tp = new TopicPartition(topic, i)
    //    //
    //    //      val clusterEarliestOffset = clusterEarliestOffsets(i)
    //    //
    //    //      val myOffset = partitionOffset.toLong
    //    //
    //    //      //将每个partition对应的offset保存到nowOffsets中，根据zk中保存的历史偏移量与kafka集群中EarliestOffset进行对比
    //    //      // hainiu_qingniu/0 -> 888
    //    //      if (myOffset >= clusterEarliestOffset) {
    //    //        nowOffset += tp -> myOffset
    //    //      } else {
    //    //        nowOffset += tp -> clusterEarliestOffset
    //    //      }
    //    //    }
    //    nowOffset
  }

}
