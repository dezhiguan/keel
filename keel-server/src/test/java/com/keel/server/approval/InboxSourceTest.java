package com.keel.server.approval;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxSourceTest {
    @Test
    void onlyDevflowNarrowsTheInbox() {
        assertThat(InboxSource.flag(null)).isEmpty();
        assertThat(InboxSource.flag("  ")).isEmpty();
        assertThat(InboxSource.flag("devflow")).isEqualTo("1");
        assertThatThrownBy(() -> InboxSource.flag("other"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
    }
}
