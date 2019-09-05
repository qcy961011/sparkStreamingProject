#!/bin/bash
source /etc/profile
delete_day=`date -d -2day '+%Y%m%d'`

echo delete_day:$delete_day

/usr/local/bin/python /home/qingniu/hainiu_cralwer/download_page/xpath_config.py

rm -rf /home/qingniu/xpath_cache_file/xpath_file${delete_day}*
hadoop fs -rmr hdfs://ns1/user/qingniu/xpath_cache_file/xpath_file${delete_day}*
