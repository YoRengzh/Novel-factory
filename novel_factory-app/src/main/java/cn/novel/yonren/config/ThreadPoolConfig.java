package cn.novel.yonren.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.*;

@Slf4j
@EnableAsync
@Configuration
@EnableConfigurationProperties(ThreadPoolConfigProperties.class)
public class ThreadPoolConfig {

    @Bean
    @ConditionalOnMissingBean(name = "threadPoolExecutor")
    public ThreadPoolExecutor threadPoolExecutor(ThreadPoolConfigProperties properties) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        // 实例化策略
        RejectedExecutionHandler handler;
        switch (properties.getPolicy()){
            case "AbortPolicy":
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
            case "DiscardPolicy":
                handler = new ThreadPoolExecutor.DiscardPolicy();
                break;
            case "DiscardOldestPolicy":
                handler = new ThreadPoolExecutor.DiscardOldestPolicy();
                break;
            case "CallerRunsPolicy":
                handler = new ThreadPoolExecutor.CallerRunsPolicy();
                break;
            default:
                handler = new ThreadPoolExecutor.AbortPolicy();
                break;
        }
        // 创建线程池
        return new ThreadPoolExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveTime(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getBlockQueueSize()),
                Executors.defaultThreadFactory(),
                handler);
    }

    /**
     * 异步生成作业专用执行器：单线程串行 + AbortPolicy。
     * 串行避免 LLM 并发花费与故事目录竞争
     * 拒绝策略必须 Abort——队列满时抛拒绝异常由 submit 转 503，绝不能用 CallerRuns 吞掉"立即返回 jobId"语义
     */
    /** 资料重建独立于小说生成，维护期间由 JobRegistry 全局门闩拒绝新的生成提交。 */
    @Bean(name = "referenceIndexExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "referenceIndexExecutor")
    public ThreadPoolExecutor referenceIndexExecutor() {
        return new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new CustomizableThreadFactory("novel-reference-index-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "jobExecutor")
    @ConditionalOnMissingBean(name = "jobExecutor")
    public ThreadPoolExecutor jobExecutor() {
        return new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16),
                new CustomizableThreadFactory("novel-job-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

}
