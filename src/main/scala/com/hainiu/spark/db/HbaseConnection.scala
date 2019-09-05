package com.hainiu.spark.db

import org.apache.hadoop.hbase.{HBaseConfiguration, TableName}
import org.apache.hadoop.hbase.client.{Connection, ConnectionFactory, HTable, Table}

object HbaseConnection {
  private lazy val connection: Connection = ConnectionFactory.createConnection(HBaseConfiguration.create())

  def getTable(tableName: String): Table = {
    connection.getTable(TableName.valueOf(tableName))
  }
}
