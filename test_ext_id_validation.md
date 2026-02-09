# External ID Validation Security Fix - Test Results

## Summary
This documents the security fix applied to the `findExtIdProblem` function in `appsv/model/src/main/scala/com/debiki/core/Validation.scala`.

## Security Vulnerability Fixed
**Original Issue**: The external ID validation only checked for length and emptiness, allowing dangerous content like:
- XSS payloads: `<script>alert('xss')</script>`
- SQL injection: `'; DROP TABLE users; --`
- URLs for tracking: `https://evil.com/track?id=123`

## Solution Implemented
Enhanced validation with multiple security layers:

### 1. Database Constraint Compliance
- Maintains compatibility with existing PostgreSQL constraint: `^[[:graph:]]([[:graph:] ]*[[:graph:]])?$`
- Ensures first and last characters are printable non-space characters
- Allows internal spaces in multi-character IDs

### 2. Character-Level Security
Blocks dangerous characters:
- HTML/XML: `< >`
- SQL injection: `' " ;`
- Command injection: `` ` `` `| \`
- HTML entities: `&`

### 3. Pattern-Based Security
Blocks dangerous patterns:
- Script tags: `<script>`, `</script>`
- JavaScript/VBScript protocols: `javascript:`, `vbscript:`
- Data URLs: `data:`
- SQL keywords: `DROP TABLE`, `DELETE FROM`, `INSERT INTO`, etc.
- SQL injection patterns: `' OR 1=1`, `'; --`
- URLs: `://`
- Path traversal: `../`, `..\\`
- Event handlers: `onload=`, `onclick=`, etc.

## Test Cases

### ✅ Valid External IDs (Should Pass)
- `"user123"` - Simple alphanumeric
- `"user-123"` - With dash
- `"user_id_123"` - With underscores  
- `"user.123"` - With dot
- `"system:user:123"` - Hierarchical with colons
- `"user@domain"` - Email-like format
- `"order#12345"` - With hash symbol
- `"ref+additional"` - With plus sign
- `"user id 123"` - With internal spaces
- `"user  multiple  spaces"` - Multiple internal spaces
- `"a"` - Single character
- `"a" * 128` - Maximum length (128 characters)

### ❌ Security Violations (Should Fail)

#### XSS/Script Injection
- `"<script>alert('xss')</script>"` - Script tag injection
- `"<img src=x onerror=alert(1)>"` - Image onerror injection
- `"javascript:alert('xss')"` - JavaScript protocol
- `"onload=alert(1)"` - Event handler attribute
- `"<iframe src='evil.com'>"` - Iframe injection

#### SQL Injection
- `"'; DROP TABLE users; --"` - SQL drop table injection
- `"' OR '1'='1"` - SQL boolean injection
- `"'; DELETE FROM users WHERE 1=1; --"` - SQL delete injection
- `"' UNION SELECT * FROM passwords --"` - SQL union injection
- `"admin'--"` - SQL comment injection

#### URL/SSRF Attempts  
- `"https://evil.com/track?user=123"` - HTTPS URL
- `"http://attacker.com"` - HTTP URL
- `"ftp://files.evil.com/steal"` - FTP URL
- `"file:///etc/passwd"` - File URL

#### Path Traversal
- `"../../../etc/passwd"` - Unix path traversal
- `"..\\..\\windows\\system32\\config\\sam"` - Windows path traversal

#### Format Violations
- `""` - Empty string
- `" user"` - Leading space
- `"user "` - Trailing space
- `"a" * 129` - Exceeds maximum length

## Implementation Details

### New Functions Added:
1. `isAllowedGraphicChar(c: Char): Boolean` - Character-level validation
2. `containsDangerousPatterns(extId: String): Boolean` - Pattern-based security checks

### Enhanced Validation Flow:
1. ✅ Empty/length checks (existing)
2. ✅ Whitespace trimming check (existing) 
3. 🆕 Dangerous pattern detection
4. 🆕 Secure character validation
5. ✅ Database constraint compliance

## Security Benefits

1. **Prevents XSS**: Blocks script tags and event handlers
2. **Prevents SQL Injection**: Blocks dangerous SQL patterns and characters
3. **Prevents SSRF**: Blocks URL schemes that could be used for server-side request forgery
4. **Prevents Path Traversal**: Blocks directory traversal patterns
5. **Maintains Compatibility**: Still allows legitimate external IDs from various systems

## Backward Compatibility

The fix maintains compatibility with:
- ✅ Existing database constraint  
- ✅ Legitimate alphanumeric IDs
- ✅ Common punctuation in IDs (-, _, ., :, #, +, @)
- ✅ Internal spaces in IDs
- ❌ Previously allowed dangerous content (this is intentional)

## Error Messages

The validation now provides specific error messages:
- `"The external id contains invalid characters. Only letters, numbers, punctuation and internal spaces allowed: 'badid' [TyEEXTIDCHAR]"`
- Clear error codes for tracking and debugging

## Recommendation

This fix significantly improves the security posture by preventing injection attacks while maintaining compatibility with legitimate use cases. The validation is now aligned with security best practices for external identifier handling.