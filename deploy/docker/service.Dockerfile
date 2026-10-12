# 四个后端服务共用这一份。镜像里没有厂商密钥。
# Server 3 拉不了公网基础镜像，所以构建发生在 GitHub runner，产物进 ACR。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY keel-common keel-common
COPY keel-gateway keel-gateway
COPY keel-server keel-server
COPY keel-audit keel-audit
COPY keel-spring-boot-starter keel-spring-boot-starter
COPY keel-llm keel-llm
ARG MODULE=keel-llm
# 提交号写进这一层。只靠 COPY 的缓存时，GHA 会把旧 jar 打进新标签。
ARG SOURCE_REV=unknown
RUN if [ "${MODULE}" = "keel-server" ]; then grep -q "CheckReport selfCheck" keel-server/src/main/java/com/keel/server/registry/model/dto/AgentDetail.java; fi
# 把刚打出来的包拷到 /out。直接从 target 拷时，构建缓存可能仍带上旧 jar。
RUN echo "source ${SOURCE_REV}" && mvn -pl :${MODULE} -am package -DskipTests \
 && mkdir -p /out \
 && cp "/src/${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar" /out/app.jar \
 && if [ "${MODULE}" = "keel-server" ]; then jar tf /out/app.jar | grep -q 'WizardCatalog.class'; fi

FROM eclipse-temurin:21-jre-jammy
ARG RAM_PERCENTAGE=60.0
ARG SOURCE_REV=unknown
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=${RAM_PERCENTAGE}"
RUN useradd --system --uid 10001 --create-home keel
WORKDIR /app
COPY --from=build --chown=10001:10001 /out/app.jar /app/app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
