/**
 * Test suite for CWE-532 fix: Audit Log Sensitive Data Anonymization
 * 
 * This test validates that sensitive data (emails, IPs, browser data) is properly
 * anonymized in audit logs before storage, addressing the security vulnerability.
 */

package tests.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.debiki.core._
import com.debiki.dao.rdb.AuditLogSiteDaoMixin

class AuditLogAnonymizationTest extends AnyFlatSpec with Matchers {

  // Mock implementation for testing the anonymization logic
  class TestAuditLogAnonymizer extends AuditLogSiteDaoMixin {
    // Implement required abstract methods for testing
    def siteId: SiteId = 1
    def runUpdateSingleRow(statement: String, values: List[AnyRef]): Unit = {}
    def runQuery[T](query: String, values: List[AnyRef], parser: java.sql.ResultSet => T): T = ???
    def runUpdate(statement: String, values: List[AnyRef]): Int = 0
    
    // Make protected methods public for testing
    def testAnonymizeSensitiveData(entry: AuditLogEntry): AuditLogEntry = anonymizeSensitiveData(entry)
    def testAnonymizeEmailAddress(email: Option[String]): Option[String] = anonymizeEmailAddress(email)
    def testAnonymizeIpAddress(ip: String): String = anonymizeIpAddress(ip)
    def testAnonymizeBrowserCookie(cookie: Option[String]): Option[String] = anonymizeBrowserCookie(cookie)
    def testAnonymizeBrowserFingerprint(fingerprint: Int): Int = anonymizeBrowserFingerprint(fingerprint)
    def testSaltAndHashForAuditLog(data: String, fieldType: String, length: Int): String = 
      saltAndHashForAuditLog(data, fieldType, length)
  }

  val anonymizer = new TestAuditLogAnonymizer()

  // ==================== EMAIL ANONYMIZATION TESTS ====================

  "Email anonymization" should "preserve domain while hashing local part" in {
    val result = anonymizer.testAnonymizeEmailAddress(Some("user@gmail.com"))
    
    result shouldBe defined
    result.get should startWith("H:")
    result.get should endWith("@gmail.com")
    result.get should not contain "user"
    
    // Should be deterministic
    val result2 = anonymizer.testAnonymizeEmailAddress(Some("user@gmail.com"))
    result shouldBe result2
  }

  it should "handle different email formats consistently" in {
    val testCases = Map(
      "simple@example.com" -> "@example.com",
      "user.name+tag@domain.co.uk" -> "@domain.co.uk",
      "123@numbers.org" -> "@numbers.org"
    )
    
    testCases.foreach { case (email, expectedDomain) =>
      val result = anonymizer.testAnonymizeEmailAddress(Some(email))
      result shouldBe defined
      result.get should (startWith("H:") and endWith(expectedDomain))
      result.get should not contain email.split("@")(0) // Local part should be hashed
    }
  }

  it should "fully hash malformed email addresses" in {
    val malformedEmails = List("not-an-email", "missing@", "@missing-local", "")
    
    malformedEmails.filterNot(_.isEmpty).foreach { email =>
      val result = anonymizer.testAnonymizeEmailAddress(Some(email))
      result shouldBe defined
      result.get should startWith("H:")
      result.get should not contain "@" // Should be fully hashed, no domain preserved
    }
  }

  it should "return None for empty emails" in {
    anonymizer.testAnonymizeEmailAddress(None) shouldBe None
    anonymizer.testAnonymizeEmailAddress(Some("")) shouldBe None
    anonymizer.testAnonymizeEmailAddress(Some("   ")) shouldBe None
  }

  it should "be idempotent (not re-hash already hashed emails)" in {
    val alreadyHashed = "H:abcd1234@example.com"
    val result = anonymizer.testAnonymizeEmailAddress(Some(alreadyHashed))
    result shouldBe Some(alreadyHashed)
  }

  // ==================== IP ADDRESS ANONYMIZATION TESTS ====================

  "IP address anonymization" should "preserve /16 network for IPv4" in {
    val testCases = Map(
      "192.168.1.100" -> "192.168.0.0",
      "10.0.255.255" -> "10.0.0.0", 
      "172.16.123.45" -> "172.16.0.0",
      "8.8.8.8" -> "8.8.0.0"
    )
    
    testCases.foreach { case (original, expected) =>
      val result = anonymizer.testAnonymizeIpAddress(original)
      result shouldBe expected
    }
  }

  it should "preserve /32 prefix for IPv6" in {
    val testCases = Map(
      "2001:db8:85a3::8a2e:370:7334" -> "2001:db8:0:0:0:0:0:0",
      "fe80::1234:5678:abcd:ef12" -> "fe80::0:0:0:0:0:0",
      "::1" -> "::0:0:0:0:0:0" // localhost case
    )
    
    testCases.foreach { case (original, expected) =>
      val result = anonymizer.testAnonymizeIpAddress(original)
      result shouldBe expected
    }
  }

  it should "preserve system/special IP addresses" in {
    val systemIps = List("127.0.0.1", "127.0.0.2", "127.0.0.4") // System, Forgotten, Sysbot
    
    systemIps.foreach { ip =>
      val result = anonymizer.testAnonymizeIpAddress(ip)
      result shouldBe ip // Should remain unchanged
    }
  }

  it should "handle already anonymized IPs idempotently" in {
    val alreadyAnonymized = List("192.168.0.0", "10.0.0.0", "H:hashvalue")
    
    alreadyAnonymized.foreach { ip =>
      val result = anonymizer.testAnonymizeIpAddress(ip)
      result shouldBe ip
    }
  }

  it should "handle malformed IP addresses gracefully" in {
    val malformedIps = List("not.an.ip", "999.999.999.999", "192.168", "")
    
    malformedIps.foreach { ip =>
      // Should not crash, returns input as-is for unknown formats
      val result = anonymizer.testAnonymizeIpAddress(ip)
      result shouldBe ip
    }
  }

  // ==================== BROWSER DATA ANONYMIZATION TESTS ====================

  "Browser cookie anonymization" should "hash non-empty cookies" in {
    val testCookies = List("session123", "user_token_abc", "long-cookie-value-here")
    
    testCookies.foreach { cookie =>
      val result = anonymizer.testAnonymizeBrowserCookie(Some(cookie))
      result shouldBe defined
      result.get should startWith("H:")
      result.get should not contain cookie
      result.get.length should be > 8 // Reasonable hash length
    }
  }

  it should "return consistent hashes for same input" in {
    val cookie = "test-cookie-123"
    val result1 = anonymizer.testAnonymizeBrowserCookie(Some(cookie))
    val result2 = anonymizer.testAnonymizeBrowserCookie(Some(cookie))
    result1 shouldBe result2
  }

  it should "handle empty/None cookies" in {
    anonymizer.testAnonymizeBrowserCookie(None) shouldBe None
    anonymizer.testAnonymizeBrowserCookie(Some("")) shouldBe Some("")
  }

  it should "be idempotent for already hashed cookies" in {
    val hashedCookie = "H:abcd1234efgh5678"
    val result = anonymizer.testAnonymizeBrowserCookie(Some(hashedCookie))
    result shouldBe Some(hashedCookie)
  }

  "Browser fingerprint anonymization" should "hash non-zero fingerprints" in {
    val testFingerprints = List(123456, 987654321, 555666777)
    
    testFingerprints.foreach { fingerprint =>
      val result = anonymizer.testAnonymizeBrowserFingerprint(fingerprint)
      result should not be fingerprint // Should be changed
      result should not be 0           // Should not be zero
      result should be > 0             // Should be positive
    }
  }

  it should "preserve zero fingerprints (NoFingerprint constant)" in {
    val result = anonymizer.testAnonymizeBrowserFingerprint(0)
    result shouldBe 0
  }

  it should "produce consistent results" in {
    val fingerprint = 123456789
    val result1 = anonymizer.testAnonymizeBrowserFingerprint(fingerprint)
    val result2 = anonymizer.testAnonymizeBrowserFingerprint(fingerprint)
    result1 shouldBe result2
  }

  // ==================== HASHING FUNCTION TESTS ====================

  "Salt and hash function" should "produce consistent results" in {
    val data = "test-data-123"
    val fieldType = "test_field"
    val length = 16
    
    val result1 = anonymizer.testSaltAndHashForAuditLog(data, fieldType, length)
    val result2 = anonymizer.testSaltAndHashForAuditLog(data, fieldType, length)
    
    result1 shouldBe result2
    result1.length shouldBe length
  }

  it should "produce different hashes for different data" in {
    val fieldType = "test_field"
    val length = 16
    
    val hash1 = anonymizer.testSaltAndHashForAuditLog("data1", fieldType, length)
    val hash2 = anonymizer.testSaltAndHashForAuditLog("data2", fieldType, length) 
    val hash3 = anonymizer.testSaltAndHashForAuditLog("data1", "different_field", length)
    
    hash1 should not be hash2 // Different data
    hash1 should not be hash3 // Different field type
  }

  it should "respect length parameter" in {
    val data = "test-data"
    val fieldType = "test"
    
    List(8, 16, 20, 32).foreach { length =>
      val result = anonymizer.testSaltAndHashForAuditLog(data, fieldType, length)
      result.length shouldBe length
    }
  }

  // ==================== INTEGRATION TESTS ====================

  "Full audit entry anonymization" should "protect all sensitive fields" in {
    val originalBrowserData = BrowserIdData(
      ip = "192.168.1.100", 
      idCookie = Some("user-session-12345"), 
      fingerprint = 987654321
    )
    
    val originalEntry = AuditLogEntry(
      siteId = 1,
      id = 1,
      batchId = None,
      didWhat = AuditLogEntryType.SignUp,
      doerTrueId = TrueId(123),
      doneAt = When.now(),
      emailAddress = Some("user@example.com"),
      browserIdData = originalBrowserData,
      browserLocation = None,
      pageId = None,
      pageType = None,
      uniquePostId = None,
      postNr = None,
      uploadHashPathSuffix = None,
      uploadFileName = None,
      sizeBytes = None,
      targetUniquePostId = None,
      targetPageId = None,
      targetPostNr = None,
      targetPatTrueId = None,
      targetSiteId = None,
      isLoading = false
    )
    
    val protectedEntry = anonymizer.testAnonymizeSensitiveData(originalEntry)
    
    // Email should be anonymized with domain preserved
    protectedEntry.emailAddress shouldBe defined
    protectedEntry.emailAddress.get should (startWith("H:") and endWith("@example.com"))
    protectedEntry.emailAddress.get should not contain "user"
    
    // IP should be masked to /16
    protectedEntry.browserIdData.ip shouldBe "192.168.0.0"
    
    // Cookie should be hashed
    protectedEntry.browserIdData.idCookie shouldBe defined
    protectedEntry.browserIdData.idCookie.get should startWith("H:")
    protectedEntry.browserIdData.idCookie.get should not contain "session"
    
    // Fingerprint should be hashed
    protectedEntry.browserIdData.fingerprint should not be originalBrowserData.fingerprint
    protectedEntry.browserIdData.fingerprint should be > 0
    
    // Non-sensitive fields should remain unchanged
    protectedEntry.siteId shouldBe originalEntry.siteId
    protectedEntry.id shouldBe originalEntry.id
    protectedEntry.didWhat shouldBe originalEntry.didWhat
    protectedEntry.doerTrueId shouldBe originalEntry.doerTrueId
  }

  it should "skip anonymization for system entries" in {
    val systemEntry = AuditLogEntry(
      siteId = 1,
      id = 2,
      batchId = None,
      didWhat = AuditLogEntryType.PageCreated,
      doerTrueId = TrueId(1), // System user
      doneAt = When.now(),
      emailAddress = None, // No email
      browserIdData = BrowserIdData.System, // System browser data
      browserLocation = None,
      pageId = Some("page123"),
      pageType = Some(PageType.Discussion),
      uniquePostId = None,
      postNr = None,
      uploadHashPathSuffix = None,
      uploadFileName = None,
      sizeBytes = None,
      targetUniquePostId = None,
      targetPageId = None,
      targetPostNr = None,
      targetPatTrueId = None,
      targetSiteId = None,
      isLoading = false
    )
    
    val result = anonymizer.testAnonymizeSensitiveData(systemEntry)
    
    // System entries should pass through unchanged
    result shouldBe systemEntry
  }

  // ==================== COMPLIANCE VALIDATION ====================

  "Anonymized audit entries" should "meet GDPR pseudonymization requirements" in {
    val sensitiveData = List(
      ("test@example.com", "192.168.1.1", Some("cookie123"), 111111),
      ("another@domain.org", "10.0.0.50", Some("session456"), 222222),
      ("user@company.com", "172.16.5.100", None, 0) 
    )
    
    sensitiveData.foreach { case (email, ip, cookie, fingerprint) =>
      val browserData = BrowserIdData(ip, cookie, fingerprint)
      
      val entry = AuditLogEntry(
        siteId = 1, id = 1, batchId = None, didWhat = AuditLogEntryType.SignUp,
        doerTrueId = TrueId(100), doneAt = When.now(),
        emailAddress = Some(email), browserIdData = browserData,
        browserLocation = None, pageId = None, pageType = None,
        uniquePostId = None, postNr = None, uploadHashPathSuffix = None,
        uploadFileName = None, sizeBytes = None, targetUniquePostId = None,
        targetPageId = None, targetPostNr = None, targetPatTrueId = None,
        targetSiteId = None, isLoading = false
      )
      
      val protected = anonymizer.testAnonymizeSensitiveData(entry)
      
      // GDPR Article 4(5): Pseudonymization verification
      // Original personal data should not be recoverable without additional information (salt)
      protected.emailAddress.get should not contain email.split("@")(0)
      protected.browserIdData.ip should not be ip
      if (cookie.isDefined) {
        protected.browserIdData.idCookie.get should not contain cookie.get
      }
      if (fingerprint > 0) {
        protected.browserIdData.fingerprint should not be fingerprint
      }
      
      // But correlation should still be possible (deterministic hashing)
      val protected2 = anonymizer.testAnonymizeSensitiveData(entry)
      protected shouldBe protected2
    }
  }

  "Anonymization process" should "be audit-trail compliant" in {
    // Verify that the anonymization process is deterministic and reversible
    // (for correlation) but not revealing original data
    
    val originalEmail = "compliance@test.com"
    val result1 = anonymizer.testAnonymizeEmailAddress(Some(originalEmail))
    val result2 = anonymizer.testAnonymizeEmailAddress(Some(originalEmail))
    
    // Deterministic for correlation
    result1 shouldBe result2
    
    // Not revealing original data
    result1.get should not contain "compliance"
    
    // But preserving operational capabilities (domain analysis)
    result1.get should endWith("@test.com")
  }
}