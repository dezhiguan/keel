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
RUN echo "source ${SOURCE_REV}" && mvn -pl :${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
ARG JAR=keel-llm/target/keel-llm-0.1.0-SNAPSHOT.jar
ARG RAM_PERCENTAGE=60.0
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=${RAM_PERCENTAGE}"
RUN useradd --system --uid 10001 --create-home keel
WORKDIR /app
COPY --from=build --chown=10001:10001 /src/${JAR} /app/app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
