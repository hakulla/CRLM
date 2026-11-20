package org.mobileagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class OperationScheduler implements AutoCloseable {
    interface Cancellation {
        boolean cancelled();
    }

    static final class Operation<T> {
        final boolean independentRead;
        final Callable<T> action;
        Operation(boolean independentRead, Callable<T> action) {
            this.independentRead = independentRead;
            this.action = action;
        }
    }

    static final class Result<T> {
        final T value;
        final Exception error;
        final boolean cancelled;
        Result(T value, Exception error, boolean cancelled) {
            this.value = value;
            this.error = error;
            this.cancelled = cancelled;
        }
    }

    private final ExecutorService reads;

    OperationScheduler(int concurrency) {
        final AtomicInteger sequence = new AtomicInteger();
        reads = Executors.newFixedThreadPool(Math.max(1, Math.min(4, concurrency)),
                new ThreadFactory() {
                    @Override public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "agent-read-" + sequence.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                });
    }

    <T> List<Result<T>> execute(List<Operation<T>> operations, Cancellation cancellation) {
        List<Result<T>> results = new ArrayList<>();
        int index = 0;
        while (index < operations.size()) {
            if (cancellation.cancelled()) {
                while (index++ < operations.size()) results.add(new Result<T>(null, null, true));
                break;
            }
            Operation<T> operation = operations.get(index);
            if (!operation.independentRead) {
                try { results.add(new Result<T>(operation.action.call(), null, false)); }
                catch (Exception error) { results.add(new Result<T>(null, error, false)); }
                index++;
                continue;
            }
            List<Future<T>> batch = new ArrayList<>();
            while (index < operations.size() && operations.get(index).independentRead) {
                final Operation<T> current = operations.get(index++);
                batch.add(reads.submit(new Callable<T>() {
                    @Override public T call() throws Exception {
                        if (cancellation.cancelled()) throw new InterruptedException("Job cancelled");
                        return current.action.call();
                    }
                }));
            }
            try {
                for (Future<T> future : batch) results.add(await(future, cancellation));
            } finally {
                for (Future<T> future : batch) {
                    if (!future.isDone()) future.cancel(true);
                }
            }
        }
        return results;
    }

    private static <T> Result<T> await(Future<T> future, Cancellation cancellation) {
        while (true) {
            try {
                if (cancellation.cancelled() && !future.isDone()) {
                    future.cancel(true);
                    return new Result<T>(null, null, true);
                }
                return new Result<T>(future.get(100, TimeUnit.MILLISECONDS), null, false);
            } catch (TimeoutException timeout) {
                continue;
            } catch (java.util.concurrent.CancellationException cancelled) {
                return new Result<T>(null, null, true);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return new Result<T>(null, cancelled, true);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                return new Result<T>(null, cause instanceof Exception ? (Exception) cause
                        : new IllegalStateException("Read Job Failed", cause), false);
            }
        }
    }

    @Override public void close() { reads.shutdownNow(); }
}
