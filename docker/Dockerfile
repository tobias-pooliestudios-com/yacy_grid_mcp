FROM gradle:4.10-jdk8-alpine as builder
MAINTAINER publicplan GmbH
LABEL MAINTAINER="publicplan GmbH"

COPY --chown=gradle:gradle ./ /app/yacy_grid_mcp
WORKDIR /app/yacy_grid_mcp

RUN gradle shadowJar && \
    mkdir data/mcp-8100/conf/ -p && \
    cp docker/config-mcp.properties data/mcp-8100/conf/config.properties && \
    cp docker/log4j.properties data/mcp-8100/conf/log4j.properties

FROM openjdk:8-alpine as runner

WORKDIR /app

COPY --from=builder /app/yacy_grid_mcp/build/libs/*.jar /app/yacy-grid-mcp.jar
COPY --from=builder /app/yacy_grid_mcp/data /app/data

EXPOSE 8100
ENTRYPOINT ["java"]
CMD ["-jar", "yacy-grid-mcp.jar"]
