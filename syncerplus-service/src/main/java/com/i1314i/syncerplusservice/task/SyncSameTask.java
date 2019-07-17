package com.i1314i.syncerplusservice.task;

import com.i1314i.syncerpluscommon.config.ThreadPoolConfig;
import com.i1314i.syncerpluscommon.util.common.TemplateUtils;
import com.i1314i.syncerpluscommon.util.spring.SpringUtil;
import com.i1314i.syncerplusservice.entity.dto.RedisSyncDataDto;
import com.i1314i.syncerplusservice.pool.ConnectionPool;
import com.i1314i.syncerplusservice.pool.Impl.CommonPoolConnectionPoolImpl;
import com.i1314i.syncerplusservice.pool.Impl.ConnectionPoolImpl;
import com.i1314i.syncerplusservice.pool.RedisClient;
import com.i1314i.syncerplusservice.pool.RedisMigrator;
import com.i1314i.syncerplusservice.service.listener.SyncerCommandListener;
import com.i1314i.syncerplusservice.util.Jedis.TestJedisClient;
import com.i1314i.syncerplusservice.util.RedisUrlUtils;
import com.i1314i.syncerplusservice.util.TaskMonitorUtils;
import com.moilioncircle.redis.replicator.*;
import com.moilioncircle.redis.replicator.cmd.Command;
import com.moilioncircle.redis.replicator.cmd.CommandListener;
import com.moilioncircle.redis.replicator.cmd.impl.DefaultCommand;
import com.moilioncircle.redis.replicator.rdb.RdbListener;
import com.moilioncircle.redis.replicator.rdb.datatype.DB;
import com.moilioncircle.redis.replicator.rdb.datatype.KeyValuePair;
import com.moilioncircle.redis.replicator.rdb.dump.datatype.DumpKeyValuePair;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

import static redis.clients.jedis.Protocol.Command.SELECT;
import static redis.clients.jedis.Protocol.toByteArray;


/**
 * 同步相同版本并且版本号>3数据
 */

@Getter
@Setter
@Slf4j
public class SyncSameTask implements Runnable {

    static ThreadPoolConfig threadPoolConfig;
    static ThreadPoolTaskExecutor threadPoolTaskExecutor;

    static {
        threadPoolConfig= SpringUtil.getBean(ThreadPoolConfig.class);
        threadPoolTaskExecutor=threadPoolConfig.threadPoolTaskExecutor();
    }

    private String sourceUri;  //源redis地址
    private String targetUri;  //目标redis地址
    private  int threadCount = 30;  //写线程数
    private boolean status=true;
    private String threadName; //线程名称
    private RedisSyncDataDto syncDataDto;



    public SyncSameTask(RedisSyncDataDto syncDataDto) {
        this.syncDataDto=syncDataDto;
        this.sourceUri = syncDataDto.getSourceUri();
        this.targetUri = syncDataDto.getTargetUri();
        this.threadName=syncDataDto.getThreadName();
        if(status){
            this.status=false;
        }
    }



    @Override
    public void run() {


        //设线程名称
        Thread.currentThread().setName(threadName);
        TaskMonitorUtils.addAliveThread(Thread.currentThread().getName(),Thread.currentThread());
        RedisURI suri = null;
        try {
            suri = new RedisURI(sourceUri);

            RedisURI turi = new RedisURI(targetUri);
            ConnectionPool pools =RedisUrlUtils.getConnectionPool();
            final ConnectionPool pool =pools;

            /**
             * 初始化连接池
             */
            pool.init(syncDataDto.getMinPoolSize(), syncDataDto.getMaxPoolSize(),syncDataDto.getMaxWaitTime(),turi,syncDataDto.getTimeBetweenEvictionRunsMillis(),syncDataDto.getIdleTimeRunsMillis());
            final AtomicInteger dbnum = new AtomicInteger(-1);
            Replicator r = RedisMigrator.dress(new RedisReplicator(suri));


            /**
             * RDB复制
             */
            r.addRdbListener(new RdbListener.Adaptor() {

                @Override
                public void handle(Replicator replicator, KeyValuePair<?> kv) {


                    RedisUrlUtils.doCheckTask(r,Thread.currentThread());

                    if(RedisUrlUtils.doThreadisCloseCheckTask())
                        return;

                    StringBuffer info = new StringBuffer();
                    if (!(kv instanceof DumpKeyValuePair)) return;
                    // Step1: select db
                    DB db = kv.getDb();
                    int index;
                    RedisClient redisClient = null;
                    try {
                         redisClient=pool.borrowResource();
                    } catch (Exception e) {
                        log.info("RDB复制：从池中获取RedisClient失败："+e.getMessage());

                    }
                    if (db != null && (index = (int) db.getDbNumber()) != dbnum.get()) {
                        status = true;

                        try {
                            redisClient.send(SELECT, toByteArray(index));
                        } catch (Exception e) {
                            log.info("RDB复制： 从池中获取链接失败: "+e.getMessage());
                        }
                        dbnum.set(index);
                        info.append("SELECT:");
                        info.append(index);
                        log.info(info.toString());
                    }

                    info.setLength(0);
                    //threadPoolTaskExecutor.execute(new SyncTask(replicator,kv,target,dbnum));
                    // Step2: restore dump data
                    DumpKeyValuePair mkv = (DumpKeyValuePair) kv;

                    if (mkv.getExpiredMs() == null) {

                        threadPoolTaskExecutor.submit(new RdbSameVersionRestoreTask(mkv, 0L,redisClient,pool ,true,info));
                    } else {
                        long ms = mkv.getExpiredMs() - System.currentTimeMillis();
                        if (ms <= 0) return;

//                      threadPoolTaskExecutor.submit(new RdbRestoreTask(mkv, ms, redisClient,pool, true,info,targetJedisplus,sourceJedisplus));
                        threadPoolTaskExecutor.submit(new RdbSameVersionRestoreTask(mkv, ms, redisClient,pool, true,info));
                    }


                }
            });


            new SyncerCommandListener(r,pool,threadPoolTaskExecutor).run();

        } catch (URISyntaxException e) {
            log.info("redis address is error:%s ", e.getMessage());
        } catch (IOException e) {
            log.info("redis address is error:%s ", e.getMessage());
        }
    }








}
