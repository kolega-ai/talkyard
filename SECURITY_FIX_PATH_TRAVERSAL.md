# Security Fix: Path Traversal Prevention in AuditLogEntry

## Issue Description

The `uploadHashPathSuffix` field in `AuditLogEntry.scala` was vulnerable to path traversal attacks. The original validation only checked for emptiness, allowing malicious patterns like `../../../etc/passwd` to be stored in the database and potentially exploited if the path is used in file system operations.

## Vulnerability Details

**File:** `appsv/model/src/main/scala/com/debiki/core/AuditLogEntry.scala`  
**CVE Classification:** CWE-22: Improper Limitation of a Pathname to a Restricted Directory ('Path Traversal')  
**Severity:** Medium to High (depending on how the field is used downstream)

### Original Vulnerable Code
```scala
require(!uploadHashPathSuffix.exists(_.trim.isEmpty), "DwE0PMF2")
```

This validation only prevented empty strings, allowing dangerous patterns:
- `../../../etc/passwd` - Classic path traversal
- `/etc/passwd` - Absolute path access
- `file\x00.jpg` - Null byte injection
- `C:\Windows\system32\cmd.exe` - Windows path access

## Security Fix Implementation

### Defense in Depth Strategy

The fix implements a two-layer validation approach:

1. **Explicit Pattern Rejection**: Fast-fail for known dangerous patterns
2. **Regex Whitelist Validation**: Only allow known-good hash path formats

### Fixed Code

```scala
object AuditLogEntry {
  /** Hash path suffix format: "0/a/bc/xyz123.jpg" or "video/12/a/bc/xyz.mp4" */
  private val HashPathSuffixRegex = 
    """^(video/)?[0-9][0-9]?/[a-z0-9]/[a-z0-9]{2}/[a-z0-9]+\.[a-z0-9]+$""".r
  
  /** Old format for backwards compatibility: "a/b/xyz123.jpg" */
  private val OldHashPathSuffixRegex = 
    """^[a-z0-9]/[a-z0-9]/[a-z0-9]+\.[a-z0-9]+$""".r

  def isValidHashPathSuffix(path: String): Boolean = {
    // Defense in depth: explicit checks before regex validation
    path.nonEmpty &&
    !path.contains("..") &&           // No path traversal
    !path.startsWith("/") &&          // No absolute paths
    !path.contains('\u0000') &&      // No null byte injection
    !path.contains("\\") &&           // No Windows-style paths
    (HashPathSuffixRegex.matches(path) || OldHashPathSuffixRegex.matches(path))
  }
}

// Updated validation in case class
require(!uploadHashPathSuffix.exists(_.trim.isEmpty), "DwE0PMF2")
require(uploadHashPathSuffix.forall(AuditLogEntry.isValidHashPathSuffix),
  "Invalid uploadHashPathSuffix format - potential path traversal [TyEHPSINVALID]")
```

### Security Properties

The fix ensures:

✅ **Path Traversal Prevention**: Rejects `..`, `./`, and similar patterns  
✅ **Absolute Path Prevention**: Rejects paths starting with `/`  
✅ **Windows Path Prevention**: Rejects backslash characters  
✅ **Null Byte Injection Prevention**: Rejects null bytes that could truncate paths  
✅ **Format Enforcement**: Only allows documented hash path formats  
✅ **Backwards Compatibility**: Supports both new and old hash path formats  
✅ **Loading Bypass**: Validation disabled during data loading (`isLoading=true`)

### Valid Path Examples

The following paths are **accepted**:
```
0/a/bc/defghijklmnopqr123456.jpg        # New format
video/12/x/yz/abcdef123456789.mp4       # Video format  
a/b/cdefghijklmn123.png                 # Old format
```

### Rejected Path Examples

The following paths are **rejected**:
```
../../../etc/passwd                      # Path traversal
/etc/passwd                             # Absolute path
0/a/../../../sensitive.txt              # Mixed traversal
file\x00.jpg                           # Null byte injection
C:\Windows\system32\cmd.exe             # Windows path
just-a-filename.jpg                     # Missing path structure
0/a/bc/FILE.JPG                        # Uppercase not allowed
```

## Testing

Comprehensive test suite added in `AuditLogEntrySecuritySpec.scala`:
- Valid format acceptance tests
- Path traversal rejection tests  
- Absolute path rejection tests
- Null byte injection rejection tests
- Windows path rejection tests
- Malformed path rejection tests
- Loading bypass verification tests

## Impact Assessment

**Breaking Change:** No - Only rejects invalid/malicious input  
**Performance Impact:** Minimal - Regex validation only on audit log entry creation  
**Security Impact:** High - Prevents potential path traversal attacks  

## Compliance

This fix addresses:
- **CWE-22**: Improper Limitation of a Pathname to a Restricted Directory  
- **OWASP Top 10 2021 - A01**: Broken Access Control  
- **NIST SP 800-53**: System and Information Integrity controls

## Verification

To verify the fix is working:

1. Valid uploads should continue working normally
2. Any attempt to create an AuditLogEntry with malicious paths will fail with `IllegalArgumentException`
3. Data loading from database continues to work (validation bypassed when `isLoading=true`)

## Related Files

- **Main Fix**: `appsv/model/src/main/scala/com/debiki/core/AuditLogEntry.scala`
- **Test Suite**: `appsv/model/src/test/scala/com/debiki/core/AuditLogEntrySecuritySpec.scala`
- **Referenced Patterns**: `appsv/server/talkyard/server/uploads/UploadsDao.scala` (regex sources)