package com.keel.server.auth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class MemoryMaintenanceAccessTest {
 @Test void allowsOnlyExplicitServiceMethods(){
  String base="/api/v1/agents/keel-assistant/memories/maintenance/";
  for(var route:java.util.List.of("claim","complete","fail")){
   assertThat(CatalogAccess.serviceAccessible("POST",base+route)).isTrue();
   assertThat(CatalogAccess.serviceAccessible("DELETE",base+route)).isFalse();
  }
  assertThat(CatalogAccess.serviceAccessible("GET",base+"status")).isTrue();
  assertThat(CatalogAccess.serviceAccessible("POST",base+"arbitrary")).isFalse();
 }
}
