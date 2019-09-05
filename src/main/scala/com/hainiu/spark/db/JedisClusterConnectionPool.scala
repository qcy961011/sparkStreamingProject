package com.hainiu.spark.db

import redis.clients.jedis._

import scala.collection.mutable.ListBuffer

object JedisClusterConnectionPool {

  private val config:JedisPoolConfig = new JedisPoolConfig()//Jedis池配置

  config.setMaxTotal(2)

  config.setMaxIdle(1)

  config.setTestOnBorrow(true)

  private val jdsInfoList = new ListBuffer[JedisShardInfo]

  jdsInfoList += new JedisShardInfo("nn1.hadoop",6379)
  jdsInfoList += new JedisShardInfo("nn2.hadoop",6379)
  jdsInfoList += new JedisShardInfo("s1.hadoop",6379)
  jdsInfoList += new JedisShardInfo("s2.hadoop",6379)
  jdsInfoList += new JedisShardInfo("s3.hadoop",6379)
  jdsInfoList += new JedisShardInfo("s4.hadoop",6379)

  import scala.collection.convert.wrapAll._

  private val pool:ShardedJedisPool = new ShardedJedisPool(config,jdsInfoList)

  def getConnection():ShardedJedisPool = {
    pool
  }

  def main(args: Array[String]): Unit = {
    val cluster: ShardedJedis = JedisClusterConnectionPool.getConnection().getResource

//    val jedis: Jedis = cluster.getShard("hainiu")
//    println(jedis.get("hainiu"))
//    println(jedis.getClient.getHost)

    val str: String = cluster.get("hainiu")
    println(str)

    JedisClusterConnectionPool.getConnection().close()
  }


}
