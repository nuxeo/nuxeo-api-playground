ARG NUXEO_VERSION=2023

FROM docker-private.packages.nuxeo.com/nuxeo/nuxeo:${NUXEO_VERSION}

ARG BUILD_TAG=unknown
ARG SCM_REF=unknown
ARG VERSION=unknown

LABEL org.nuxeo.api-playground.build-tag=$BUILD_TAG
LABEL org.nuxeo.api-playground.scm-ref=$SCM_REF
LABEL org.nuxeo.api-playground.version=$VERSION

COPY --chown=900:0 target/nuxeo-api-playground-package-$VERSION.zip /packages/
