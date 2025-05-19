FROM maven:3.9.9-eclipse-temurin-24-alpine AS build
COPY . .

RUN mvn clean package -DskipTests
RUN ls -al /target

FROM openjdk:21-jdk-slim
COPY --from=build /target/checker-0.0.1-SNAPSHOT.jar checker.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "checker.jar"]