FROM alpine:3.6

# Alpine 3.6 installs ccache 3.3.4.
# See https://pkgs.alpinelinux.org/packages?name=ccache&branch=v3.6&arch=x86_64
RUN apk add --no-cache ccache

# Create mountpoint for ccache volume.
RUN mkdir -p /ccache

# Volume mounts inherit the permissions of the path they are mounted on.
RUN chown 1014:1014 /ccache

# Declare /ccache a volume (cannot be changed after this point)
VOLUME /ccache

# Point ccache to the right directory.
# See https://ccache.samba.org/manual.html#_configuration
ENV CCACHE_DIR /ccache

# Add cleanup script
ADD ./clean.sh /clean.sh

# Run as jenkins user (ccache files are owned by the jenkins user).
USER 1014:1014

# Run cleanup by default
CMD ["/clean.sh"]
