package common.v1;

public enum ConcurrentRootKind {
    THREAD_ENTRY_LAMBDA,
    RUNNABLE_RUN,
    CALLABLE_CALL,
    EXECUTOR_TASK_BODY,
    THREAD_START_CALLER
}
