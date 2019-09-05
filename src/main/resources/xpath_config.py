# -*- encoding: utf-8 -*-
'''
Created on 2017/7/1 13:49
Copyright (c) 2017/7/1, 海牛学院版权所有.
@author: 青牛
'''
import sys

sys.path.append('/home/qingniu/hainiu_crawler')

from commons.util.db_util import DBUtil
from commons.util.log_util import LogUtil
from commons.util.file_util import FileUtil
from commons.util.time_util import TimeUtil
from hdfs.client import Client
from configs import config
import time, sys, redis


def xpath_config_file():
    select_xpath_rule_sql = """select host,xpath,type from stream_extract_xpath_rule where host='%s' and status=0"""
    rl = LogUtil().get_base_logger()
    try:
        # _HAINIU_DB = {'HOST': '192.168.137.190', 'USER': 'hainiu', 'PASSWD': '12345678', 'DB': 'hainiucrawler',
        #             'CHARSET': 'utf8', 'PORT': 3306}
        d = DBUtil(config._HAINIU_DB)
        #d = DBUtil(_HAINIU_DB)
        r = redis.Redis('nn1.hadoop', 6379, db=6)
        #r = redis.Redis('redis.hadoop', 6379, db=6)
        f = FileUtil()
        t = TimeUtil()
        c = Client("http://nn1.hadoop:50070")

        time_str = t.now_time(format='%Y%m%d%H%M%S')
        #local_xpath_file_path = '/Users/leohe/Data/input/xpath_cache_file/xpath_file' + time_str
        local_xpath_file_path = '/home/qingniu/xpath_cache_file/xpath_file' + time_str

        start_cursor = 0
        is_finish = True
        starttime = time.clock()
        host_set = set()

        while is_finish:
            values = set()
            limit = r.scan(start_cursor,'total:*',10)
            if limit[0] == 0:
                is_finish = False
            start_cursor = limit[0]
            for h in limit[1]:
                host = h.split(":")[1]
                total_key = h
                txpath_key = 'txpath:%s' % host
                fxpath_key = 'fxpath:%s' % host
                total = r.get(total_key)

                txpath = r.zrevrange(txpath_key, 0, 1)
                row_format = "%s\t%s\t%s\t%s"
                if txpath:
                    # print 'txpath:%s' % txpath
                    txpath_num = int(r.zscore(txpath_key, txpath[0]))
                    if txpath.__len__() == 2:
                        txpath_num_1 = int(r.zscore(txpath_key, txpath[1]))
                        txpath_num_1 = txpath_num_1 if txpath_num_1 is not None else 0

                    # print 'txpath_max_num:%s' % txpath_num
                    if txpath_num / float(total) >= 0.8:
                        values.add(row_format % (host, txpath[0], 'true', '0'))
                        host_set.add(host)
                    else:
                        if txpath_num >= 1:
                            values.add(row_format % (host, txpath[0], 'true', '0'))
                            host_set.add(host)
                        if txpath_num_1 is not None and txpath_num_1 >= 1:
                            values.add(row_format % (host, txpath[1], 'true', '0'))
                            host_set.add(host)

                fxpath = r.smembers(fxpath_key)
                if fxpath:
                    # print 'fxpath:%s' % fxpath
                    for fx in fxpath:
                        values.add(row_format % (host, fx, 'false', '0'))
                    host_set.add(host)

                sql = select_xpath_rule_sql % host
                list_rule = d.read_tuple(sql)
                for rule in list_rule:
                    type = rule[2]
                    if type == 0:
                        values.add(row_format % (rule[0], rule[1], 'true', '2'))
                        host_set.add(host)
                    elif type == 1:
                        values.add(row_format % (rule[0], rule[1], 'false', '3'))
                        host_set.add(host)

            f.write_file_line_pattern(local_xpath_file_path, values, "a")
        #上传到HDFS的XPATH配置文件目录
        c.upload("/user/qingniu/xpath_cache_file/", local_xpath_file_path)
        endtime = time.clock()
        worksec = int(round((endtime - starttime)))
        rl.info('total host %s,action time %s\'s' % (host_set.__len__(), worksec))
    except:
        rl.exception()
        d.rollback()
    finally:
        d.close()


if __name__ == '__main__':
    reload(sys)
    sys.setdefaultencoding('utf-8')
    xpath_config_file()
