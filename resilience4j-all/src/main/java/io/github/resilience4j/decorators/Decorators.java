package io.github.resilience4j.decorators;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.cache.Cache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.CallableUtils;
import io.github.resilience4j.core.CompletionStageUtils;
import io.github.resilience4j.core.SupplierUtils;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.*;

/**
 * A Decorator builder which can be used to apply multiple decorators to a Supplier, Callable
 * Function, Runnable, CompletionStage or Consumer.
 * <p>
 * Decorators are applied in the order of the builder chain. For example, consider:
 *
 * <pre>{@code
 * Supplier<String> supplier = Decorators
 *     .ofSupplier(() -> service.method())
 *     .withCircuitBreaker(CircuitBreaker.ofDefaults("id"))
 *     .withRetry(Retry.ofDefaults("id"))
 *     .withFallback(CallNotPermittedException.class, e -> service.fallbackMethod())
 *     .decorate();
 * }</pre>
 *
 * This results in the following composition when executing the supplier: <br>
 * <pre>Fallback(Retry(CircuitBreaker(Supplier)))</pre>
 *
 * This means the Supplier is called first, then its result is handled by the CircuitBreaker, then Retry and then Fallback.
 * Each Decorator makes its own determination whether an exception represents a failure.
 */
public interface Decorators {

    static <T> DecorateSupplier<T> ofSupplier(Supplier<T> supplier) {
        return new DecorateSupplier<>(supplier);
    }

    static <T, R> DecorateFunction<T, R> ofFunction(Function<T, R> function) {
        return new DecorateFunction<>(function);
    }

    static DecorateRunnable ofRunnable(Runnable runnable) {
        return new DecorateRunnable(runnable);
    }

    static <T> DecorateCallable<T> ofCallable(Callable<T> callable) {
        return new DecorateCallable<>(callable);
    }

    static <T> DecorateCompletionStage<T> ofCompletionStage(
        Supplier<CompletionStage<T>> stageSupplier) {
        return new DecorateCompletionStage<>(stageSupplier);
    }

    static <T> DecorateConsumer<T> ofConsumer(Consumer<T> consumer) {
        return new DecorateConsumer<>(consumer);
    }

    class DecorateSupplier<T> {

        private Supplier<T> supplier;

        private DecorateSupplier(Supplier<T> supplier) {
            this.supplier = supplier;
        }


        public DecorateSupplier<T> withCircuitBreaker(CircuitBreaker circuitBreaker) {
            supplier = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
            return this;
        }

        public DecorateSupplier<T> withRetry(Retry retryContext) {
            supplier = Retry.decorateSupplier(retryContext, supplier);
            return this;
        }

        public <K> DecorateFunction<K, T> withCache(Cache<K, T> cache) {
            return Decorators.ofFunction(Cache.decorateSupplier(cache, supplier));
        }

        public DecorateSupplier<T> withRateLimiter(RateLimiter rateLimiter) {
            return withRateLimiter(rateLimiter, 1);
        }

        public DecorateSupplier<T> withRateLimiter(RateLimiter rateLimiter, int permits) {
            supplier = RateLimiter.decorateSupplier(rateLimiter, permits, supplier);
            return this;
        }

        public DecorateSupplier<T> withBulkhead(Bulkhead bulkhead) {
            supplier = Bulkhead.decorateSupplier(bulkhead, supplier);
            return this;
        }

        public DecorateSupplier<T> withFallback(Function<Throwable, T> exceptionHandler) {
            supplier = SupplierUtils.recover(supplier, exceptionHandler);
            return this;
        }

        public DecorateSupplier<T> withFallback(Predicate<T> resultPredicate, UnaryOperator<T> resultHandler) {
            supplier = SupplierUtils.recover(supplier, resultPredicate, resultHandler);
            return this;
        }

        public DecorateSupplier<T> withFallback(BiFunction<T, Throwable, T> handler) {
            supplier = SupplierUtils.andThen(supplier, handler);
            return this;
        }

        public DecorateSupplier<T> withFallback(List<Class<? extends Throwable>> exceptionTypes, Function<Throwable, T> exceptionHandler) {
            supplier = SupplierUtils.recover(supplier, exceptionTypes, exceptionHandler);
            return this;
        }

        public DecorateCompletionStage<T> withThreadPoolBulkhead(ThreadPoolBulkhead threadPoolBulkhead) {
            return Decorators.ofCompletionStage(getCompletionStageSupplier(threadPoolBulkhead));
        }

        private Supplier<CompletionStage<T>> getCompletionStageSupplier(
            ThreadPoolBulkhead threadPoolBulkhead) {
            return () -> {
                try {
                    return threadPoolBulkhead.executeSupplier(supplier);
                } catch (BulkheadFullException ex) {
                    CompletableFuture<T> future = new CompletableFuture<>();
                    future.completeExceptionally(ex);
                    return future;
                }
            };
        }

        public Supplier<T> decorate() {
            return supplier;
        }

        public T get() {
            return supplier.get();
        }
    }

    class DecorateFunction<T, R> {

        private Function<T, R> function;

        private DecorateFunction(Function<T, R> function) {
            this.function = function;
        }

        public DecorateFunction<T, R> withCircuitBreaker(CircuitBreaker circuitBreaker) {
            function = CircuitBreaker.decorateFunction(circuitBreaker, function);
            return this;
        }

        public DecorateFunction<T, R> withRetry(Retry retryContext) {
            function = Retry.decorateFunction(retryContext, function);
            return this;
        }

        public DecorateFunction<T, R> withRateLimiter(RateLimiter rateLimiter) {
            return withRateLimiter(rateLimiter, 1);
        }

        public DecorateFunction<T, R> withRateLimiter(RateLimiter rateLimiter, int permits) {
            function = RateLimiter.decorateFunction(rateLimiter, permits, function);
            return this;
        }

        public DecorateFunction<T, R> withRateLimiter(RateLimiter rateLimiter,
            Function<T, Integer> permitsCalculator) {
            function = RateLimiter.decorateFunction(rateLimiter, permitsCalculator, function);
            return this;
        }

        public DecorateFunction<T, R> withBulkhead(Bulkhead bulkhead) {
            function = Bulkhead.decorateFunction(bulkhead, function);
            return this;
        }

        public Function<T, R> decorate() {
            return function;
        }

        public R apply(T t) {
            return function.apply(t);
        }
    }

    class DecorateRunnable {

        private Runnable runnable;

        private DecorateRunnable(Runnable runnable) {
            this.runnable = runnable;
        }

        public DecorateRunnable withCircuitBreaker(CircuitBreaker circuitBreaker) {
            runnable = CircuitBreaker.decorateRunnable(circuitBreaker, runnable);
            return this;
        }

        public DecorateRunnable withRetry(Retry retryContext) {
            runnable = Retry.decorateRunnable(retryContext, runnable);
            return this;
        }

        public DecorateRunnable withRateLimiter(RateLimiter rateLimiter) {
            return withRateLimiter(rateLimiter, 1);
        }

        public DecorateRunnable withRateLimiter(RateLimiter rateLimiter, int permits) {
            runnable = RateLimiter.decorateRunnable(rateLimiter, permits, runnable);
            return this;
        }

        public DecorateRunnable withBulkhead(Bulkhead bulkhead) {
            runnable = Bulkhead.decorateRunnable(bulkhead, runnable);
            return this;
        }

        public DecorateCompletionStage<Void> withThreadPoolBulkhead(ThreadPoolBulkhead threadPoolBulkhead) {
            return Decorators.ofCompletionStage(getCompletionStageSupplier(threadPoolBulkhead));
        }

        private Supplier<CompletionStage<Void>> getCompletionStageSupplier(
            ThreadPoolBulkhead threadPoolBulkhead) {
            return () -> {
                try {
                    return threadPoolBulkhead.executeRunnable(runnable);
                } catch (BulkheadFullException ex) {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    future.completeExceptionally(ex);
                    return future;
                }
            };
        }

        public Runnable decorate() {
            return runnable;
        }

        public void run() {
            runnable.run();
        }
    }

    class DecorateCallable<T> {

        private Callable<T> callable;

        private DecorateCallable(Callable<T> callable) {
            this.callable = callable;
        }


        public DecorateCallable<T> withCircuitBreaker(CircuitBreaker circuitBreaker) {
            callable = CircuitBreaker.decorateCallable(circuitBreaker, callable);
            return this;
        }

        public DecorateCallable<T> withRetry(Retry retryContext) {
            callable = Retry.decorateCallable(retryContext, callable);
            return this;
        }

        public DecorateCallable<T> withRateLimiter(RateLimiter rateLimiter) {
            return withRateLimiter(rateLimiter, 1);
        }

        public DecorateCallable<T> withRateLimiter(RateLimiter rateLimiter, int permits) {
            callable = RateLimiter.decorateCallable(rateLimiter, permits, callable);
            return this;
        }

        public DecorateCallable<T> withBulkhead(Bulkhead bulkhead) {
            callable = Bulkhead.decorateCallable(bulkhead, callable);
            return this;
        }

        public DecorateCallable<T> withFallback(BiFunction<T, Throwable, T> handler) {
            callable = CallableUtils.andThen(callable, handler);
            return this;
        }

        public DecorateCallable<T> withFallback(Predicate<T> resultPredicate, UnaryOperator<T> resultHandler) {
            callable = CallableUtils.recover(callable, resultPredicate, resultHandler);
            return this;
        }

        public DecorateCallable<T> withFallback(List<Class<? extends Throwable>> exceptionTypes, Function<Throwable, T> exceptionHandler) {
            callable = CallableUtils.recover(callable, exceptionTypes, exceptionHandler);
            return this;
        }

        public DecorateCallable<T> withFallback(Function<Throwable, T> exceptionHandler) {
            callable = CallableUtils.recover(callable, exceptionHandler);
            return this;
        }

        public <X extends Throwable> DecorateCallable<T> withFallback(Class<X> exceptionType, Function<Throwable, T> exceptionHandler) {
            callable = CallableUtils.recover(callable, exceptionType, exceptionHandler);
            return this;
        }

        public DecorateCompletionStage<T> withThreadPoolBulkhead(ThreadPoolBulkhead threadPoolBulkhead) {
            return Decorators.ofCompletionStage(getCompletionStageSupplier(threadPoolBulkhead));
        }

        private Supplier<CompletionStage<T>> getCompletionStageSupplier(
            ThreadPoolBulkhead threadPoolBulkhead) {
            return () -> {
                try {
                    return threadPoolBulkhead.executeCallable(callable);
                } catch (BulkheadFullException ex) {
                    CompletableFuture<T> future = new CompletableFuture<>();
                    future.completeExceptionally(ex);
                    return future;
                }
            };
        }

        public Callable<T> decorate() {
            return callable;
        }

        public T call() throws Exception {
            return callable.call();
        }
    }

    class DecorateCompletionStage<T> {

        private Supplier<CompletionStage<T>> stageSupplier;

        public DecorateCompletionStage(Supplier<CompletionStage<T>> stageSupplier) {
            this.stageSupplier = stageSupplier;
        }

        public DecorateCompletionStage<T> withCircuitBreaker(CircuitBreaker circuitBreaker) {
            stageSupplier = CircuitBreaker.decorateCompletionStage(circuitBreaker, stageSupplier);
            return this;
        }

        public DecorateCompletionStage<T> withRetry(Retry retryContext,
            ScheduledExecutorService scheduler) {
            stageSupplier = Retry.decorateCompletionStage(retryContext, scheduler, stageSupplier);
            return this;
        }

        public DecorateCompletionStage<T> withBulkhead(Bulkhead bulkhead) {
            stageSupplier = Bulkhead.decorateCompletionStage(bulkhead, stageSupplier);
            return this;
        }

        public DecorateCompletionStage<T> withTimeLimiter(TimeLimiter timeLimiter,
            ScheduledExecutorService scheduler) {
            stageSupplier =  timeLimiter.decorateCompletionStage(scheduler, stageSupplier);
            return this;
        }

        public DecorateCompletionStage<T> withRateLimiter(RateLimiter rateLimiter) {
            return withRateLimiter(rateLimiter, 1);
        }

        public DecorateCompletionStage<T> withRateLimiter(RateLimiter rateLimiter, int permits) {
            stageSupplier = RateLimiter
                .decorateCompletionStage(rateLimiter, permits, stageSupplier);
            return this;
        }

        public DecorateCompletionStage<T> withFallback(Predicate<T> resultPredicate, UnaryOperator<T> resultHandler) {
            stageSupplier = CompletionStageUtils.recover(stageSupplier, resultPredicate, resultHandler);
            return this;
        }

        public DecorateCompletionStage<T> withFallback(BiFunction<T, Throwable, T> handler) {
            stageSupplier = CompletionStageUtils.andThen(stageSupplier, handler);
            return this;
        }

        public DecorateCompletionStage<T> withFallback(List<Class<? extends Throwable>> exceptionTypes, Function<Throwable, T> exceptionHandler) {
            stageSupplier = CompletionStageUtils.recover(stageSupplier, exceptionTypes, exceptionHandler);
            return this;
        }

        public DecorateCompletionStage<T> withFallback(Function<Throwable, T> exceptionHandler) {
            stageSupplier = CompletionStageUtils.recover(stageSupplier, exceptionHandler);
            return this;
        }

        public <X extends Throwable> DecorateCompletionStage<T> withFallback(Class<X> exceptionType, Function<Throwable, T> exceptionHandler) {
            stageSupplier = CompletionStageUtils.recover(stageSupplier, exceptionType, exceptionHandler);
            return this;
        }

        public Supplier<CompletionStage<T>> decorate() {
            return stageSupplier;
        }

        public CompletionStage<T> get() {
            return stageSupplier.get();
        }
    }

    class DecorateConsumer<T> {

        private Consumer<T> consumer;

        private DecorateConsumer(Consumer<T> consumer) {
            this.consumer = consumer;
        }

        public DecorateConsumer<T> withCircuitBreaker(CircuitBreaker circuitBreaker) {
            consumer = CircuitBreaker.decorateConsumer(circuitBreaker, consumer);
            return this;
        }

        public DecorateConsumer<T> withRateLimiter(RateLimiter rateLimiter) {
            return withRateLimiter(rateLimiter, 1);
        }

        public DecorateConsumer<T> withRateLimiter(RateLimiter rateLimiter, int permits) {
            consumer = RateLimiter.decorateConsumer(rateLimiter, permits, consumer);
            return this;
        }

        public DecorateConsumer<T> withRateLimiter(RateLimiter rateLimiter,
            Function<T, Integer> permitsCalculator) {
            consumer = RateLimiter.decorateConsumer(rateLimiter, permitsCalculator, consumer);
            return this;
        }

        public DecorateConsumer<T> withBulkhead(Bulkhead bulkhead) {
            consumer = Bulkhead.decorateConsumer(bulkhead, consumer);
            return this;
        }

        public Consumer<T> decorate() {
            return consumer;
        }

        public void accept(T t) {
            consumer.accept(t);
        }
    }
}
