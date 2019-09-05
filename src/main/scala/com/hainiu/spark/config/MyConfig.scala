package com.hainiu.spark.config

object MyConfig {
  // xpath配置文件存放地址
//  final val XPATH_INFO_DIR: String = "/Users/leohe/Data/input/xpath_cache_file"
  final val XPATH_INFO_DIR: String = "hdfs://ns1/user/qingniu/xpath_cache_file"

  // 更新xpath配置文件所在的地址
  final val UPDATE_XPATH_INTERVAL: Long = 10000L

  // hbase 表名
  final val HBASE_TABLE_NAME: String = "context_extract"

  // mysql 配置
  final val MYSQL_CONFIG: Map[String, String] = Map("url" -> "jdbc:mysql://nn1.hadoop/hainiucrawler", "username" -> "hainiu", "password" -> "12345678")
//  final val MYSQL_CONFIG: Map[String, String] = Map("url" -> "jdbc:mysql://nn1.hadoop/hainiu_crawler", "username" -> "hainiu_work", "password" -> "jhUJErmgaztqonzT")

  // redis 配置
  final val REDIS_CONFIG: Map[String, String] = Map("host" -> "redis.hadoop", "port" -> "6379", "timeout" -> "10000")
//  final val REDIS_CONFIG: Map[String, String] = Map("host" -> "nn1.hadoop", "port" -> "6379", "timeout" -> "10000")

  //KAFKA的组
  final val KAFKA_GROUP:String = "qingniu"

  //KAFKA的topic
  final val KAFKA_TOPIC:String = "hainiu_html"

  //KAFKA的broker
  final val KAFKA_BROKER:String = "nn1.hadoop:9092,nn2.hadoop:9092,s1.hadoop:9092"
//  final val KAFKA_BROKER:String = "s1.hadoop:9092,s2.hadoop:9092,s3.hadoop:9092,s4.hadoop:9092,s5.hadoop:9092,s6.hadoop:9092,s7.hadoop:9092,s8.hadoop:9092"

  //KAFKA的offset读取位置
  final val KAFKA_OFFSET_POSITION:String = "earliest"

  //ES的host
  final val ES_HOST:String = "s1.hadoop"

  //ES的端口
  final val ES_PORT:String = "9200"

  //ES的index和type
  final val ES_INDEX_TYPE:String = "hainiu_spark/news"

  //ZK配置
  final val ZOOKEEPER:String = "nn1.hadoop:2181,nn2.hadoop:2181,s1.hadoop:2181"

  // spark的多目录输入和输出
  //  final val URL_FILE_DIR: String = "/Users/leohe/Data/input/hainiu_cralwer/*/*"  （"/Users/leohe/Data/input/hainiu_cralwer/201710/31"）
  //  多目录输出，例子：https://blog.csdn.net/lw_ghy/article/details/51477100

}
