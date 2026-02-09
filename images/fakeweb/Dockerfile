FROM denoland/deno:1.20.1

# Nice to haves:
RUN \
  apt-get update && \
  apt-get install -y \
      tree \
      curl \
      net-tools \
      # Oddly enough, 'ps' not installed:
      procps


# Copied from:  https://github.com/denoland/deno_docker#as-a-dockerfile
# License: MIT, see ./LICENSE.

# The port that your application listens to.
EXPOSE 8090

WORKDIR /app

# Create a non-root user and set proper permissions
# The deno user already exists in the base image, so we'll use it
RUN chown -R deno:deno /deno-dir && \
    chown -R deno:deno /app

# Switch to deno user for dependency caching and app compilation
USER deno

# Cache the dependencies as a layer (the following two steps are re-run only when deps.ts is modified).
# Ideally cache deps.ts will download and compile _all_ external files used in main.ts.
COPY --chown=deno:deno app/deps.ts .
RUN deno cache deps.ts

# These steps will be re-run upon each file change in your working directory:
COPY --chown=deno:deno app .
# Compile the main app so that it doesn't need to be compiled each startup/entry.
RUN deno cache main.ts
# ------


# The entrypoint script is no longer needed since we're running as deno user directly
CMD ["run", "--allow-net", "main.ts"]
