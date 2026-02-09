# Security Fix: Docker Container Running as Root

## Problem Fixed
The Gulp Docker container was running as root by default, which created a security vulnerability (CWE-269: Improper Privilege Management). This violated the principle of least privilege and could potentially allow privilege escalation attacks if the container were compromised.

## Solution Implemented

### 1. Dockerfile Changes (`images/gulp/Dockerfile`)
- **Added non-root user**: Created `nodeuser` with UID 1001 and GID 1001 by default
- **Added build arguments**: `USER_UID` and `USER_GID` for flexible UID/GID configuration at build time
- **Added USER instruction**: Container now defaults to running as `nodeuser` instead of root
- **Set proper ownership**: Ensured `/opt/talkyard/server` is owned by `nodeuser`

### 2. Entrypoint Script Changes (`images/gulp/entrypoint.sh`)
- **Enhanced privilege detection**: Added logic to detect whether running as root or non-root
- **Maintained backward compatibility**: Still supports dynamic user creation when running as root
- **Added non-root execution path**: When running as non-root, executes commands directly with proper environment
- **Added helpful warnings**: Warns about potential file permission issues in development scenarios

## Security Benefits
✅ **Container no longer starts as root by default**  
✅ **Follows Docker security best practices**  
✅ **Maintains existing functionality**  
✅ **Provides flexibility for different deployment scenarios**

## Usage

### Standard Usage (Secure by Default)
```bash
# Build and run with default non-root user (UID 1001)
docker build -t talkyard-gulp images/gulp/
docker run --rm talkyard-gulp gulp
```

### Development with Volume Mounts
For development scenarios where you need file ownership to match your host user:

**Option 1: Runtime user override**
```bash
docker run --user "$(id -u):$(id -g)" -v $(pwd):/opt/talkyard/server --rm talkyard-gulp gulp
```

**Option 2: Build-time UID configuration**
```bash
docker build --build-arg USER_UID=$(id -u) --build-arg USER_GID=$(id -g) -t talkyard-gulp images/gulp/
docker run -v $(pwd):/opt/talkyard/server --rm talkyard-gulp gulp
```

**Option 3: Override to root (if absolutely necessary)**
```bash
docker run --user root -v $(pwd):/opt/talkyard/server --rm talkyard-gulp gulp
```

### Production Deployment
The container is now secure by default for production use:
```bash
docker build -t talkyard-gulp:prod images/gulp/
docker run --rm talkyard-gulp:prod gulp build
```

## Backward Compatibility
- Existing docker-compose configurations will continue to work
- The entrypoint script still handles dynamic user creation when running as root
- All existing functionality is preserved

## Security Compliance
This fix addresses:
- **CWE-269**: Improper Privilege Management
- **OWASP A04:2021**: Insecure Design
- **Docker Security Best Practices**: Running containers as non-root users