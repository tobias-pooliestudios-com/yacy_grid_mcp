FROM gradle:5.4-jdk8-alpine as builder
MAINTAINER publicplan GmbH
LABEL MAINTAINER="publicplan GmbH"

COPY --chown=gradle:gradle ./ /app/yacy_grid_mcp
WORKDIR /app/yacy_grid_mcp

RUN gradle build --info && \
    mkdir build/distributions/app && \
    unzip build/distributions/*.zip -d build/distributions/app && \
    mkdir data/mcp-8100/conf/ -p && \
    cp -rf conf/* data/mcp-8100/conf/

FROM openjdk:8-alpine as runner

COPY --from=builder /app/yacy_grid_mcp/build/distributions/app/* /app
COPY --from=builder /app/yacy_grid_mcp/data /app/data
# COPY --from=builder /app/yacy_grid_mcp/conf /app/conf

WORKDIR /app
EXPOSE 8100
ENTRYPOINT ["/bin/sh"]
CMD ["bin/yacy_grid_mcp"]
