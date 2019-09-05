package com.hainiu.spark.broadcast

import org.apache.spark.util.AccumulatorV2

import scala.collection.mutable.HashMap

class MapAccumulator extends AccumulatorV2[(Any, Any), HashMap[Any, Any]]{

  private var map = new HashMap[Any, Any]()

  override def isZero: Boolean = map.isEmpty

  override def reset(): Unit = {
    map = new HashMap[Any, Any]()
  }

  override def copy(): AccumulatorV2[(Any, Any), HashMap[Any, Any]] = {
    val accumulator = new MapAccumulator()
    accumulator.synchronized(map.foreach(accumulator.map += _))
    accumulator
  }

  override def add(v: (Any, Any)): Unit = {
    var value: Long = map.getOrElse(v._1,0L).asInstanceOf[Long]
    value += v._2.asInstanceOf[Long]
    map += v._1 -> value
  }

  override def merge(other: AccumulatorV2[(Any, Any), HashMap[Any, Any]]): Unit = {
    other match {
      case o: MapAccumulator => o.map.foreach(f => {
        var value: Long = map.getOrElse(f._1,0L).asInstanceOf[Long]
        value += f._2.asInstanceOf[Long]
        map += f._1 -> value
      })
    }
  }

  override def value: HashMap[Any, Any] = map
}
