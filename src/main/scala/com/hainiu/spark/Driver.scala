package com.hainiu.spark

import com.hainiu.spark.extract.NewsExtractStreaming
import org.apache.hadoop.util.ProgramDriver

object Driver {
  def main(args: Array[String]): Unit = {
    val driver = new ProgramDriver
    driver.addClass("newsextractstreaming",classOf[NewsExtractStreaming],"流式新闻抽取")
    driver.run(args)
  }
}
