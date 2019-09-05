package com.hainiu.spark.broadcast

import java.io.{ObjectInputStream, ObjectOutputStream}

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.streaming.StreamingContext

import scala.reflect.ClassTag

case class BroadcastWapper[T: ClassTag](@transient private val ssc: StreamingContext,
                                    @transient private val _v: T) {
  private var v: Broadcast[T] = ssc.sparkContext.broadcast(_v)

  def update(newValue: T, blocking: Boolean = false): Unit = {
    //手动取消广播变量的持久化
    v.unpersist(blocking)

    //重新创建广播变量
    v = ssc.sparkContext.broadcast(newValue)
  }

  def value: T = v.value

  private def writeObject(out: ObjectOutputStream): Unit = {
    out.writeObject(v)
  }

  private def readObject(in: ObjectInputStream): Unit = {
    v = in.readObject().asInstanceOf[Broadcast[T]]
  }
}
