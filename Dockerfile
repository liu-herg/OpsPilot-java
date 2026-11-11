# 第一阶段：编译。--mount=type=cache 把 Maven 本地仓库缓存在 Docker 里，改代码重新构建时不用重新下载依赖。
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# 第二阶段：运行。只保留 JRE 和 jar。
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS=""
COPY --from=build /workspace/target/opspilot-0.1.0.jar /app/opspilot.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/opspilot.jar"]
