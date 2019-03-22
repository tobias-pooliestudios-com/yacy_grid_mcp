FROM gradle:4.10-jdk8-alpine as builder
MAINTAINER publicplan GmbH
LABEL MAINTAINER="publicplan GmbH"

COPY --chown=gradle:gradle ./ /app/yacy_grid_mcp
WORKDIR /app/yacy_grid_mcp

RUN gradle shadowJar --info && \
    mkdir data/mcp-8100/conf/ -p && \
    cp -rf conf/* data/mcp-8100/conf/

FROM openjdk:8-alpine as runner

WORKDIR /app

COPY --from=builder /app/yacy_grid_mcp/build/libs/*.jar /app/yacy-grid-mcp.jar
COPY --from=builder /app/yacy_grid_mcp/data /app/data
COPY --from=builder /app/yacy_grid_mcp/conf /app/conf

EXPOSE 8100
ENTRYPOINT ["java"]
CMD ["-jar", "yacy-grid-mcp.jar"]
