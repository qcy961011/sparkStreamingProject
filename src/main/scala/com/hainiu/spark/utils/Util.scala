package com.hainiu.spark.utils

import java.text.SimpleDateFormat

import cn.wanghaomiao.xpath.model.JXDocument
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import java.util
import java.util.Date

object Util {
  /**
    * 根据正反规则的xpath从dom中抽取正文
    * 从java中移植过来的
    */
  def getContext(doc: Document, positiveXpath: mutable.HashSet[String], negativeXpath: mutable.HashSet[String]): String = {
    val jx: JXDocument = new JXDocument(doc)
    val positiveElementList: List[Elements] = getElementsList(jx, positiveXpath)
    val negativeElementList: List[Elements] = getElementsList(jx, negativeXpath)

    //将所有反规则的元素从dom中移除
    try {
      for (elements: Elements <- negativeElementList) {
//        val value: Array[Element] = elements.toArray().asInstanceOf[Array[Element]]
        val value = elements.iterator()
        while (value.hasNext) {
          val element: Element = value.next()
          element.remove()
        }
      }
    } catch {
      case e: ClassCastException => e.printStackTrace()
    }

    // 获取正规则节点的内容
    var context: String = ""
    for (elements: Elements <- positiveElementList) {
      val text: String = elements.text()
      if (text != null) context += text
    }

    context
  }

  private def getElementsList(jx: JXDocument, xpathSet: mutable.HashSet[String]): List[Elements] = {
    val list: ListBuffer[Elements] = new ListBuffer[Elements]

    if (xpathSet == null || xpathSet.isEmpty) {
      list.toList
    } else {
      for (xpath <- xpathSet) {
        val sel = jx.sel(xpath).asInstanceOf[util.List[Element]]
        val eles: Elements = new Elements(sel)
        list += eles
      }
      list.toList
    }
  }

  def getCurrentTime: String = {
    val now: Date = new Date()
    val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
    dateFormat.format(now)
  }

  def getTime(time: Long, pattern: String): String = {
    val dateFormat: SimpleDateFormat = new SimpleDateFormat(pattern)
    dateFormat.format(time)
  }

  def main(args: Array[String]): Unit = {
    println(getCurrentTime)

    println(getTime(new Date().getTime, "yyyy-MM-dd"))
  }
}