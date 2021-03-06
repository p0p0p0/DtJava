package com.github.tingyugetc520.ali.dingtalk.message;

import com.github.tingyugetc520.ali.dingtalk.api.DtService;
import com.github.tingyugetc520.ali.dingtalk.bean.message.DtEventMessage;
import com.github.tingyugetc520.ali.dingtalk.error.DtRuntimeException;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;

/**
 * <pre>
 * 消息路由器，通过代码化的配置，把来自钉钉的消息交给handler处理
 *
 * 说明：
 * 1. 配置路由规则时要按照从细到粗的原则，否则可能消息可能会被提前处理
 * 2. 默认情况下消息只会被处理一次，除非使用 {@link DtMessageRouterRule#next()}
 * 3. 规则的结束必须用{@link DtMessageRouterRule#end()}或者{@link DtMessageRouterRule#next()}，否则不会生效
 *
 * 使用方法：
 * DtMessageRouter router = new DtMessageRouter();
 * router
 *   .rule()
 *       .eventType("eventType")
 *       .interceptor(interceptor, ...).handler(handler, ...)
 *   .end()
 *   .rule()
 *       // 另外一个匹配规则
 *   .end()
 * ;
 *
 * // 将DtMessage交给消息路由器
 * router.route(message);
 *
 * </pre>
 */
@Slf4j
public class DtMessageRouter {
    private static final int DEFAULT_THREAD_POOL_SIZE = 100;
    private final List<DtMessageRouterRule> rules = new ArrayList<>();

    private final DtService dtService;

    private ExecutorService executorService;

    private DtErrorExceptionHandler exceptionHandler;

    /**
     * 构造方法.
     */
    public DtMessageRouter(DtService dtService) {
        this.dtService = dtService;
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("DtMessageRouter-pool-%d").build();
        this.executorService = new ThreadPoolExecutor(DEFAULT_THREAD_POOL_SIZE, DEFAULT_THREAD_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), namedThreadFactory);

        this.exceptionHandler = new DtLogExceptionHandler();
    }

    /**
     * <pre>
     * 设置自定义的 {@link ExecutorService}
     * 如果不调用该方法，默认使用 Executors.newFixedThreadPool(100)
     * </pre>
     */
    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * <pre>
     * 设置自定义的{@link DtErrorExceptionHandler}
     * 如果不调用该方法，默认使用 {@link DtLogExceptionHandler}
     * </pre>
     */
    public void setExceptionHandler(DtErrorExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    List<DtMessageRouterRule> getRules() {
        return this.rules;
    }

    /**
     * 开始一个新的Route规则.
     */
    public DtMessageRouterRule rule() {
        return new DtMessageRouterRule(this);
    }

    /**
     * 处理消息.
     */
    public boolean route(final DtEventMessage message, final Map<String, Object> context) {
        // 消息为空，则说明回调有问题
        if (Objects.isNull(message)) {
            throw new DtRuntimeException("回调消息为空");
        }

        final List<DtMessageRouterRule> matchRules = new ArrayList<>();
        // 收集匹配的规则
        for (final DtMessageRouterRule rule : this.rules) {
            if (rule.test(message)) {
                matchRules.add(rule);
                if (!rule.isReEnter()) {
                    break;
                }
            }
        }

        // 没有处理器则默认回调成功
        if (matchRules.size() == 0) {
            return true;
        }

        boolean res = false;
        final List<Future> futures = new ArrayList<>();
        for (final DtMessageRouterRule rule : matchRules) {
            // 返回最后一个非异步的rule的执行结果
            if (rule.isAsync()) {
                futures.add(
                        this.executorService.submit(() -> {
                            rule.service(message, context, DtMessageRouter.this.dtService, DtMessageRouter.this.exceptionHandler);
                        })
                );
            } else {
                res = rule.service(message, context, this.dtService, this.exceptionHandler);
            }
        }

        if (futures.size() > 0) {
            this.executorService.submit(() -> {
                for (Future future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException e) {
                        log.error("Error happened when wait task finish", e);
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException e) {
                        log.error("Error happened when wait task finish", e);
                    }
                }
            });
        }
        return res;
    }

    /**
     * 处理消息.
     */
    public boolean route(final DtEventMessage message) {
        return this.route(message, new HashMap<>(2));
    }

}
