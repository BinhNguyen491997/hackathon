# Build: đóng gói jar ngay trong image để deploy được ở bất cứ đâu
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

# Runtime: JRE gọn, chạy bằng user thường (không root)
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S agent && adduser -S agent -G agent
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER agent

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Health check để AgentBase / K8s biết container đã sẵn sàng
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
    CMD wget -qO- http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
