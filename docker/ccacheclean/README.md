# ccache-clean

Because the ccachesync process randomly dumps files from other
machines in the ccache base directory, ccache itself won't trigger
cleanup in time. To avoid running ouf of disk space we trigger this
cleanup step manually.

Initially, we just ran ccache -c at an interval. But this cleanup
trigger always removes stuff, not only if the total cache size exceeds
the configured maximum size. To avoid this, we add a check for the
size of the cache, and only trigger cleanup when it exceeds the
maximum size.

This directory contains the Dockerfile and cleanup script that
implements this.
