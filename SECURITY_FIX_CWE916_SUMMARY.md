# Security Fix for CWE-916: Weak Password Storage Validation

## Summary

Fixed CWE-916 vulnerability in password storage validation by implementing comprehensive security improvements to address weak password hashing parameters and eliminate timing attack vulnerabilities.

## Root Cause Analysis

The original implementation had several security concerns that triggered CWE-916:

1. **Cleartext password support in production code** - Primary trigger for the vulnerability
2. **SCrypt parameters below modern recommendations** (n=65536 vs recommended n=131072)  
3. **Non-constant-time string comparison** in cleartext branch vulnerable to timing attacks
4. **No hash upgrade mechanism** for strengthening old password hashes

## Security Improvements Implemented

### 1. Enhanced Password Hashing Parameters

**Before:** 
- SCrypt parameters: n=65536 (2^16), r=8, p=1
- Memory usage: 64MB

**After:**
- SCrypt parameters: n=131072 (2^17), r=8, p=1 (OWASP 2024 recommended)
- Memory usage: 128MB
- Introduced versioned hash prefixes (`scrypt2:` for stronger hashes)

### 2. Eliminated Timing Attack Vulnerabilities

**Before:**
```scala
plainTextPassword == cleartext  // Vulnerable to timing attacks
```

**After:**  
```scala
constantTimeStringEquals(plainTextPassword, cleartext)  // Constant-time comparison

private def constantTimeStringEquals(a: String, b: String): Boolean = {
  if (a.length != b.length) return false
  var result = 0
  for (i <- a.indices) {
    result |= a.charAt(i) ^ b.charAt(i)
  }
  result == 0
}
```

### 3. Production Environment Protection

**Before:** Cleartext passwords allowed in any environment

**After:** 
- Environment validation prevents cleartext in production
- Security logging for unauthorized cleartext attempts
- Test-only cleartext support with strict environment checks

```scala
val isTestEnvironment = sys.env.getOrElse("ENVIRONMENT", "production") == "test" ||
                       sys.props.get("testing").contains("true")

if (!isTestEnvironment) {
  System.err.println(s"SECURITY WARNING: Cleartext password attempted in production environment")
  false
}
```

### 4. Automatic Hash Upgrade System

Implemented transparent password hash upgrades:

```scala
def checkPasswordWithUpgrade(plainTextPassword: String, hash: String): PasswordCheckResult = {
  // Returns: ValidPassword, InvalidPassword, or ValidPasswordNeedsUpgrade
}
```

When a user with an old hash successfully logs in:
- Password is verified with legacy parameters
- Hash is automatically upgraded to stronger parameters  
- User database record is updated seamlessly
- Security audit trail is logged

### 5. Backward Compatibility

- Legacy `scrypt:` hashes continue to work 
- New `scrypt2:` hashes use stronger parameters
- API maintains existing boolean return type for compatibility
- Graceful error handling prevents login failures during upgrades

## Files Modified

### `/appsv/model/src/main/scala/com/debiki/core/dao-db.scala`
- **Enhanced password verification with upgrade detection**
- **Strengthened SCrypt parameters** (n=65536 → n=131072)  
- **Added constant-time string comparison**
- **Implemented versioned hash format**
- **Added environment validation for cleartext passwords**

### `/appsv/rdb/src/main/scala/com/debiki/dao/rdb/LoginSiteDaoMixin.scala`
- **Enhanced `_loginWithPassword()` method**
- **Integrated automatic hash upgrade logic**
- **Added security audit logging**
- **Graceful error handling for upgrade failures**

## Security Compliance

✅ **CWE-916: Use of Password Hash With Insufficient Computational Effort**
- Upgraded to OWASP 2024 recommended SCrypt parameters
- Eliminated cleartext password vulnerabilities
- Implemented automatic hash strengthening

✅ **Timing Attack Prevention**
- Constant-time string comparisons
- Eliminated information leakage through execution timing

✅ **Defense in Depth**
- Environment validation
- Security audit logging  
- Graceful degradation
- Backward compatibility

## Migration Strategy

1. **Automatic and Transparent**: Existing users' password hashes are automatically upgraded when they successfully log in
2. **No Service Disruption**: Legacy hashes continue to work during transition period
3. **Audit Trail**: All upgrades are logged for security monitoring
4. **Rollback Safe**: Changes maintain full backward compatibility

## Testing Considerations

- **Legacy Hash Compatibility**: Verify old `scrypt:` hashes still authenticate correctly
- **New Hash Generation**: Confirm new passwords use `scrypt2:` format with stronger parameters
- **Environment Protection**: Test cleartext rejection in production environment  
- **Upgrade Mechanism**: Verify automatic hash upgrades occur on successful login
- **Error Handling**: Ensure upgrade failures don't prevent valid logins

## Performance Impact

- **Minimal for existing users**: Login time unchanged until hash upgrade  
- **~2x compute time for new passwords**: Due to doubled memory usage (64MB → 128MB)
- **One-time upgrade cost**: Each user experiences upgrade cost once during their next successful login
- **Improved security posture**: Stronger resistance to brute-force attacks

## Conclusion

This comprehensive fix addresses the root cause of CWE-916 by eliminating weak password storage validation patterns, strengthening cryptographic parameters to current standards, and implementing robust security controls while maintaining backward compatibility and operational stability.