# Build image of the ClusterFuzzLite fuzzers (see build.sh). Pinned by digest; Dependabot or a manual bump
# moves it. tcl is needed to build the SQLite amalgamation, libssl-dev is SQLCipher's crypto provider.
FROM gcr.io/oss-fuzz-base/base-builder@sha256:1f9cf74bc3d7029b528494542f9d9851b256dceed38d3f420989434dc5ae0760

RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl-dev tcl zip \
    && rm -rf /var/lib/apt/lists/*

COPY . $SRC/sealite
WORKDIR $SRC/sealite
COPY .clusterfuzzlite/build.sh $SRC/build.sh
