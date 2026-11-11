package com.opspilot.run;

import com.opspilot.config.OpsPilotProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStatusTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.WAITING_APPROVAL)).isTrue();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.READY_TO_SUBMIT)).isTrue();
        assertThat(RunStatus.WAITING_APPROVAL.canTransitionTo(RunStatus.READY_TO_SUBMIT)).isTrue();
        assertThat(RunStatus.WAITING_APPROVAL.canTransitionTo(RunStatus.REJECTED)).isTrue();
        assertThat(RunStatus.READY_TO_SUBMIT.canTransitionTo(RunStatus.COMPLETED)).isTrue();
    }

    @Test
    void cannotSkipApprovalOrLeaveTerminalStates() {
        // 等待审批的运行不能直接完成，必须先批准
        assertThat(RunStatus.WAITING_APPROVAL.canTransitionTo(RunStatus.COMPLETED)).isFalse();
        // 没启动的运行不能直接提交付款
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.READY_TO_SUBMIT)).isFalse();
        for (RunStatus terminal : new RunStatus[]{RunStatus.COMPLETED, RunStatus.REJECTED, RunStatus.FAILED}) {
            assertThat(terminal.isTerminal()).isTrue();
            for (RunStatus target : RunStatus.values()) {
                assertThat(terminal.canTransitionTo(target)).as(terminal + " -> " + target).isFalse();
            }
        }
    }

    @Test
    void onlyStatesThatNeedAnExecutorAreRecoverable() {
        // 用户还没点启动
        assertThat(RunStatus.QUEUED.isRecoverable()).isFalse();
        assertThat(RunStatus.RUNNING.isRecoverable()).isTrue();
        assertThat(RunStatus.READY_TO_SUBMIT.isRecoverable()).isTrue();
        // 等审批是在等人，不是卡住了
        assertThat(RunStatus.WAITING_APPROVAL.isRecoverable()).isFalse();
        assertThat(RunStatus.COMPLETED.isRecoverable()).isFalse();
    }

    @Test
    void runStoreRejectsIllegalTransitionBeforeTouchingDatabase() {
        // mapper 传 null：如果真的走到数据库会 NPE，这里应该先抛 IllegalStateException
        RunStore store = new RunStore(null, new OpsPilotProperties(
                new OpsPilotProperties.Systems("", "", "", 1000, 1000), 10000, 30,
                new OpsPilotProperties.Chaos(false),
                new OpsPilotProperties.Worker("", 60, 5000, 10, 4, 5)));
        assertThatThrownBy(() -> store.transition("run-x", RunStatus.WAITING_APPROVAL, RunUpdate.to(RunStatus.COMPLETED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WAITING_APPROVAL -> COMPLETED");
    }

    @Test
    void backoffDoublesAndIsCappedAtFiveMinutes() {
        assertThat(RunStore.backoffSeconds(0)).isEqualTo(5);
        assertThat(RunStore.backoffSeconds(1)).isEqualTo(10);
        assertThat(RunStore.backoffSeconds(3)).isEqualTo(40);
        assertThat(RunStore.backoffSeconds(6)).isEqualTo(300);
        assertThat(RunStore.backoffSeconds(50)).isEqualTo(300);
    }
}
