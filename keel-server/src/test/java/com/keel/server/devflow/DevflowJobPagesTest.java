package com.keel.server.devflow;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class DevflowJobPagesTest {
    private DevflowTypes.Job job(String id,String agent) {
        return DevflowTypes.Job.create(id,id,agent,"COLLAB","user","org",null,"RUN","goal",10,3,List.of());
    }
    @Test void agentHistoryIsFilteredBeforePagingAndTheBoardRemainsCompatible() {
        var jobs=List.of(job("1","qa"),job("2","other"),job("3","qa"),job("4","qa"));
        var board=new DevflowTypes.JobList(null,jobs);
        assertThat(DevflowJobPages.page(board,null,null,null).get("items")).isEqualTo(jobs);
        var first=DevflowJobPages.page(board,"qa",null,2);
        assertThat(first.get("items")).isEqualTo(List.of(jobs.get(0),jobs.get(2)));
        assertThat(first.get("nextCursor")).isEqualTo("3");
        var next=DevflowJobPages.page(board,"qa","3",2);
        assertThat(next.get("items")).isEqualTo(List.of(jobs.get(3)));assertThat(next.get("nextCursor")).isNull();
        assertThatThrownBy(()->DevflowJobPages.page(board,"qa","2",2)).isInstanceOf(com.keel.server.common.KeelException.class);
    }
}
