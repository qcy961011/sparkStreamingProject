package com.hainiu.spark.db

import java.{lang, util}

import redis.clients.jedis.{Jedis, JedisPool, JedisPoolConfig}
import com.hainiu.spark.config.MyConfig.REDIS_CONFIG

object JedisConnectionPool {

  private val config = new JedisPoolConfig

  //最大的连接数
  config.setMaxTotal(36)

  //最大的空闲数
  config.setMaxIdle(18)

  //保持连接活跃
  config.setTestOnBorrow(true)

  //得到连接池，设置超时时间，单位是毫秒，10000就是10秒
  //  private val pool = new JedisPool(config,"redis.hadoop",6379,10000,"hainiu666")
  private val pool = new JedisPool(config, REDIS_CONFIG("host"), REDIS_CONFIG("port").toInt, REDIS_CONFIG("timeout").toInt)

  def getConnection(): Jedis = {

    pool.getResource

  }


  def main(args: Array[String]): Unit = {

    //连接池的使用
    val jedis: Jedis = JedisConnectionPool.getConnection()

    jedis.select(6)
    //    var z = ArrayBuffer[String]("1","2")
    //    jedis.sadd("hobbys", z:_*)
    //    jedis.flushDB()
    //    jedis.zadd("price", 1, "1")
    //    jedis.zadd("price", 5, "10")
    //    jedis.zadd("price", 2, "5.2")
    //    jedis.zadd("price", 6, "6")
    //    jedis.zadd("price", 7, "3")
    //    jedis.zincrby("price", 10, "五百")

    // 给指定元素的增加分值，返回新的分值
    //    val s: Double = jedis.zincrby("price", 5.5, "3")
    //    System.out.println(s)

    // 指定键在集合的分值排名，从0开始算
    import scala.collection.convert.wrapAll._
    val r: util.Set[String] = jedis.zrevrange("txpath:news.stnn.cc", 0, 0)
    println(r)

    for (i <- r) {
      val score: lang.Double = jedis.zscore("txpath:news.stnn.cc", i)
      println(score)
    }

    //    conn.set("hainiu", "1000")
    //
    //    val r1: String = conn.get("hainiu")
    //
    //    println(r1)
    //
    //    conn.incrBy("hainiu", -50)
    //
    //    val r2: String = conn.get("hainiu")
    //
    //    println(r2)

    //    val rs: util.Set[String] = jedis.keys("*")
    //
    //    import scala.collection.JavaConversions._
    //    for (r <- rs) {
    //      println(s"${r}:${jedis.get(r)}")
    //    }

    jedis.close()


    //客户端使用
    //    val jedis = new Jedis("nn1.hadoop",6379,10000)
    //    jedis.auth("hainiu666")
    //    jedis.set("hainiu2","3")
    //    println(jedis.get("hainiu2"))
    //    jedis.close()

  }

}
