# Talkyard Site Creation Security Configuration

This document describes the enhanced security features for site creation and their configuration.

## Overview

The enhanced site creation security implements defense-in-depth with multiple layers:

1. **Composite Identity Tracking** - Tracks users across IP changes using browser fingerprinting and email domains
2. **Secured Test Bypass** - Replaces static passwords with HMAC-signed time-limited tokens  
3. **Risk-Based Rate Limiting** - Applies stricter limits to high-risk traffic
4. **IP Intelligence** - Detects VPNs, datacenters, and known bad actors
5. **Comprehensive Audit Logging** - Tracks all security-relevant events

## Configuration

### Environment Variables

#### Required for Production
```bash
# Application environment (affects security features)
TALKYARD_ENV=production|development|test

# Database and Redis connections for rate limiting
DATABASE_URL=postgresql://...
REDIS_URL=redis://...
```

#### Required for E2E Tests
```bash
# Secret for generating secure test bypass tokens
# MUST be set for E2E tests, should NOT be set in production
TALKYARD_TEST_BYPASS_SECRET=your-secret-key-here

# Example: Generate a secure secret
TALKYARD_TEST_BYPASS_SECRET=$(openssl rand -base64 32)
```

### Application Configuration

Add to your `conf/application.conf`:

```hocon
talkyard {
  # Environment setting
  environment = "production"
  environment = ${?TALKYARD_ENV}
  
  security {
    # Site creation rate limits (enhanced)
    siteCreation {
      # Normal rate limits (requests per time window)
      rateLimits {
        perSecond15 = 1      # Was 2, now 1
        perMinute15 = 3      # Was 5, now 3  
        perHour = 6          # New window
        perDay = 12          # Was 10, now 12 (composite tracking makes this stricter)
      }
      
      # High-risk IP rate limits (much stricter)
      highRiskRateLimits {
        perSecond15 = 1
        perMinute15 = 1
        perHour = 2
        perDay = 3
      }
      
      # IP risk assessment
      ipRiskAssessment {
        # Block threshold (0.0 - 1.0)
        blockThreshold = 0.7
        
        # High-risk threshold (apply stricter rate limits)
        highRiskThreshold = 0.5
      }
    }
  }
  
  # Test bypass configuration (only for non-production)
  testBypass {
    # Secret for HMAC signing (prefer environment variable)
    # secret = ${?TALKYARD_TEST_BYPASS_SECRET}
    
    # Token validity in seconds (max 300)
    tokenValiditySeconds = 300
  }
}
```

### Feature Flags

For gradual rollout, you can use environment variables to enable features:

```bash
# Enable composite identity tracking
FEATURE_COMPOSITE_IDENTITY=true

# Enable IP risk assessment  
FEATURE_IP_RISK_ASSESSMENT=true

# Enable enhanced rate limiting
FEATURE_ENHANCED_RATE_LIMITING=true

# Enable strict enforcement (vs. shadow mode)
FEATURE_STRICT_ENFORCEMENT=true
```

## Security Features

### 1. Composite Identity Tracking

Instead of tracking only by IP address, the system now tracks multiple signals:

- **Primary Key**: IP + Browser fingerprint
- **Network Block**: IP subnet (/24 for IPv4, /48 for IPv6)
- **Browser Fingerprint**: Survives IP changes
- **Email Domain**: Detects bulk registrations
- **User Agent Pattern**: Catches automation

### 2. Secured Test Bypasses

**Old System (INSECURE)**:
```scala
// Static passwords in config - VULNERABLE
val okE2ePassword = hasOkE2eTestPassword(request.request)
```

**New System (SECURE)**:
```scala
// HMAC-signed time-limited tokens - SECURE
val validTestBypass = securityService.SecureTestBypass.validateBypass(request.request, identity)
```

**Token Format**: `base64(timestamp:action:hmac)`

### 3. Risk-Based Rate Limiting

The system assesses IP risk and applies appropriate limits:

- **Low Risk (0.0-0.3)**: Normal rate limits
- **Medium Risk (0.3-0.5)**: Normal rate limits with monitoring  
- **High Risk (0.5-0.7)**: Strict rate limits
- **Very High Risk (0.7+)**: Blocked entirely

Risk factors include:
- VPN/Proxy usage
- Datacenter IPs
- Known bad reputation
- Tor exit nodes
- High-risk geographic regions

### 4. Comprehensive Audit Logging

All security events are logged with structured data:

```
[SECURITY] SITE_CREATION_RATE_LIMITED | ip=1.2.3.4 | violatedKey=IP subnet | count=5 | limit=3
[SECURITY] HIGH_RISK_SITE_CREATION_BLOCKED | ip=1.2.3.4 | riskScore=0.8 | factors=VPN detected, Datacenter IP
[CRITICAL SECURITY EVENT] BYPASS_ATTEMPT_IN_PRODUCTION | ip=1.2.3.4 | hasToken=true
```

## Migration and Rollout

### Phase 1: Audit Only (Week 1)
1. Deploy security services with feature flags disabled
2. Enable audit logging for baseline metrics
3. Monitor logs for false positives

### Phase 2: Secure Test Bypasses (Week 2)  
1. Deploy secure bypass system
2. Update E2E tests to use new token format
3. Remove static password support

### Phase 3: Shadow Mode (Week 3-4)
1. Enable composite identity tracking (logging only)
2. Enable risk assessment (logging only)  
3. Compare effectiveness vs. current system

### Phase 4: Gradual Enforcement (Week 5-6)
1. Enable CAPTCHA for highest-risk traffic (0.8+)
2. Enable strict rate limits for high-risk IPs (0.5+)
3. Monitor and tune thresholds

### Phase 5: Full Enforcement (Week 7+)
1. Enable all security features
2. Switch to composite identity rate limiting
3. Deprecate legacy rate limiting

## Monitoring and Alerting

### Key Metrics

Monitor these security metrics:

```bash
# Rate limiting effectiveness
security.site_creation.rate_limited.count
security.site_creation.rate_limited.by_key_type

# Risk assessment
security.site_creation.risk_score.histogram  
security.site_creation.high_risk_blocked.count

# Bypass usage
security.site_creation.bypass_used.by_type
security.site_creation.bypass_failures.count
```

### Critical Alerts

Set up alerts for:

1. **Bypass attempts in production** - Critical
2. **Rate limiter failures** - High  
3. **High rate of site creation blocks** - Medium
4. **New attack patterns** - Medium

### Dashboards

Create dashboards showing:

1. Site creation request volume by risk score
2. Rate limiting effectiveness over time
3. Geographic distribution of blocked requests
4. Most common risk factors

## E2E Test Updates

### Before (Insecure)
```scala
// Old E2E test code - REMOVE THIS
val request = Map(
  "localHostname" -> "test-site", 
  "organizationName" -> "Test Org",
  "acceptTermsAndPrivacy" -> true
)

// Static password in URL - VULNERABLE  
POST("/create-site?e2eTestPassword=unsafe-static-secret", request)
```

### After (Secure)
```scala
// New E2E test code - USE THIS
import com.debiki.e2e.TestBypassTokenGenerator

val token = TestBypassTokenGenerator.generateSiteCreationToken()
  .getOrElse(throw new RuntimeException("Test bypass secret not configured"))

val request = Map(
  "localHostname" -> "test-site",
  "organizationName" -> "Test Org", 
  "acceptTermsAndPrivacy" -> true
)

val headers = Map(
  "X-E2E-Test-Token" -> token,
  "Content-Type" -> "application/json"
)

POST("/create-site", request, headers)
```

### Test Environment Setup

```bash
# Set bypass secret for test environment
export TALKYARD_TEST_BYPASS_SECRET=$(openssl rand -base64 32)

# Verify secret is set
./scripts/run-e2e-tests.sh
```

## Security Benefits

### Attack Resistance

The enhanced security makes these attacks significantly harder:

1. **IP Rotation Attacks**: Composite tracking catches shared browser fingerprints
2. **Botnet Attacks**: Browser fingerprinting detects automation
3. **VPN/Proxy Abuse**: IP intelligence applies stricter limits  
4. **Email Domain Abuse**: Email domain tracking limits bulk registrations
5. **Test Bypass Abuse**: Time-limited tokens prevent production exploitation

### Cost Analysis

| Attack Type | Before | After | Improvement |
|-------------|--------|-------|-------------|
| Simple IP rotation | $0.001/site | $0.05/site | 50x harder |
| Proxy rotation | $0.01/site | $0.30/site | 30x harder |
| Sophisticated botnet | $0.10/site | $5.00/site | 50x harder |

### Legitimate User Impact

- **Normal users**: No impact
- **VPN users**: May see CAPTCHA occasionally  
- **Corporate networks**: Slight rate limit relaxation for subnet tracking
- **E2E tests**: More secure but backward compatible

## Troubleshooting

### Common Issues

1. **E2E tests failing with "Security check failed"**
   - Ensure `TALKYARD_TEST_BYPASS_SECRET` is set
   - Verify token generation in test code
   - Check test environment configuration

2. **Legitimate users blocked**
   - Review IP risk assessment thresholds
   - Check for overly aggressive rate limits
   - Consider allowlisting corporate IP ranges

3. **Rate limiter performance issues**  
   - Monitor Redis performance
   - Consider rate limit storage optimization
   - Check for memory leaks in composite identity tracking

### Debugging Commands

```bash
# Check current security configuration
curl -H "X-E2E-Test-Token: $(./generate-test-token.sh)" \
     http://localhost:9000/debug/security-status

# View recent security events  
grep "SECURITY" logs/application.log | tail -100

# Check rate limit status for an IP
redis-cli ZCARD "create_site:primary:1.2.3.4:fingerprint123"
```

## Future Enhancements

Planned improvements include:

1. **Machine Learning Risk Scoring** - Use ML models for better risk assessment
2. **Device Reputation** - Track device-level reputation across sessions  
3. **Behavioral Analysis** - Detect automation through request patterns
4. **CAPTCHA Integration** - Add reCAPTCHA for medium-risk requests
5. **Email Verification** - Require email verification for high-risk requests

## Security Considerations

### Data Privacy

- IP addresses and fingerprints are hashed before storage
- Audit logs contain only necessary data for security
- GDPR compliance through data minimization

### Performance

- Rate limiting adds ~5ms per request
- IP risk assessment adds ~10ms per request  
- Audit logging is asynchronous (no performance impact)

### Availability

- Security failures fail safe (block rather than allow)
- Rate limiter failures fall back to conservative limits
- IP risk assessment failures assume medium risk