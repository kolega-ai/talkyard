/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.debiki.core

import org.scalatest._
import org.scalatest.freespec._

/**
 * Test cases for the security fix that prevents path traversal attacks
 * in uploadHashPathSuffix field validation.
 */
class AuditLogEntrySecuritySpec extends AnyFreeSpec with matchers.should.Matchers {
  
  "AuditLogEntry.isValidHashPathSuffix" - {
    
    "should accept valid new format hash paths" in {
      // Normal new format: size/char/chars/hash.ext
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/defghijk123.jpg") should be (true)
      AuditLogEntry.isValidHashPathSuffix("12/z/xy/abc123def456.png") should be (true)
      AuditLogEntry.isValidHashPathSuffix("9/0/ab/xyz123456789.pdf") should be (true)
      
      // Video format: video/size/char/chars/hash.ext  
      AuditLogEntry.isValidHashPathSuffix("video/0/a/bc/xyz123.mp4") should be (true)
      AuditLogEntry.isValidHashPathSuffix("video/99/z/ab/file.webm") should be (true)
    }
    
    "should accept valid old format hash paths" in {
      // Old format: char/char/hash.ext
      AuditLogEntry.isValidHashPathSuffix("a/b/cdefghijk.jpg") should be (true)
      AuditLogEntry.isValidHashPathSuffix("z/9/abc123xyz.png") should be (true)
      AuditLogEntry.isValidHashPathSuffix("0/x/file123hash.pdf") should be (true)
    }
    
    "should reject path traversal attempts" in {
      // Basic path traversal
      AuditLogEntry.isValidHashPathSuffix("../../../etc/passwd") should be (false)
      AuditLogEntry.isValidHashPathSuffix("0/a/../../../etc/passwd") should be (false)
      AuditLogEntry.isValidHashPathSuffix("..") should be (false)
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/..") should be (false)
      
      // Relative path elements
      AuditLogEntry.isValidHashPathSuffix("0/./bc/file.jpg") should be (false)
      AuditLogEntry.isValidHashPathSuffix("0/a/../bc/file.jpg") should be (false)
    }
    
    "should reject absolute paths" in {
      AuditLogEntry.isValidHashPathSuffix("/etc/passwd") should be (false)
      AuditLogEntry.isValidHashPathSuffix("/0/a/bc/file.jpg") should be (false)
      AuditLogEntry.isValidHashPathSuffix("/home/user/file.txt") should be (false)
    }
    
    "should reject null byte injection attempts" in {
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/file\u0000.jpg") should be (false)
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/file.jpg\u0000") should be (false)
    }
    
    "should reject Windows-style paths" in {
      AuditLogEntry.isValidHashPathSuffix("0\\a\\bc\\file.jpg") should be (false)
      AuditLogEntry.isValidHashPathSuffix("..\\..\\etc\\passwd") should be (false)
      AuditLogEntry.isValidHashPathSuffix("C:\\Windows\\system32\\cmd.exe") should be (false)
    }
    
    "should reject empty or whitespace-only paths" in {
      AuditLogEntry.isValidHashPathSuffix("") should be (false)
      AuditLogEntry.isValidHashPathSuffix("   ") should be (false)
      AuditLogEntry.isValidHashPathSuffix("\t\n") should be (false)
    }
    
    "should reject malformed hash paths" in {
      // No extension
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/file") should be (false)
      
      // Just filename without path structure
      AuditLogEntry.isValidHashPathSuffix("just-a-filename.jpg") should be (false)
      
      // Wrong number of path segments
      AuditLogEntry.isValidHashPathSuffix("too/many/path/segments/here/file.jpg") should be (false)
      AuditLogEntry.isValidHashPathSuffix("0/a/file.jpg") should be (false)
      
      // Uppercase characters not allowed
      AuditLogEntry.isValidHashPathSuffix("0/A/BC/FILE.JPG") should be (false)
      
      // Invalid characters
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/file@hash.jpg") should be (false)
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/file with spaces.jpg") should be (false)
      
      // Trailing slash
      AuditLogEntry.isValidHashPathSuffix("0/a/bc/") should be (false)
      
      // Leading slash without being absolute (caught by regex)
      AuditLogEntry.isValidHashPathSuffix("/0/a/bc/file.jpg") should be (false)
    }
    
    "should reject encoded traversal attempts" in {
      // URL-encoded path traversal (shouldn't match regex anyway)
      AuditLogEntry.isValidHashPathSuffix("%2e%2e/%2e%2e/etc/passwd") should be (false)
      AuditLogEntry.isValidHashPathSuffix("..%2f..%2fetc/passwd") should be (false)
      
      // Mixed encoding
      AuditLogEntry.isValidHashPathSuffix("0/a/bc%2ffile.jpg") should be (false)
    }
  }
  
  "AuditLogEntry validation" - {
    
    "should reject entries with malicious uploadHashPathSuffix when not loading" in {
      val badEntry = AuditLogEntry(
        siteId = "testsite",
        id = 123,
        didWhat = AuditLogEntryType.UploadFile,
        doerTrueId = TrueId(234),
        doneAt = new java.util.Date(),
        browserIdData = BrowserIdData("", 0, None, None),
        uploadHashPathSuffix = Some("../../../etc/passwd"),
        isLoading = false
      )
      
      // This should throw an exception due to invalid path
      val exception = intercept[IllegalArgumentException] {
        badEntry
      }
      exception.getMessage should include("Invalid uploadHashPathSuffix")
    }
    
    "should accept entries with valid uploadHashPathSuffix" in {
      val goodEntry = AuditLogEntry(
        siteId = "testsite", 
        id = 123,
        didWhat = AuditLogEntryType.UploadFile,
        doerTrueId = TrueId(234),
        doneAt = new java.util.Date(),
        browserIdData = BrowserIdData("", 0, None, None),
        uploadHashPathSuffix = Some("0/a/bc/validhash123.jpg"),
        isLoading = false
      )
      
      // Should not throw any exception
      goodEntry.uploadHashPathSuffix should be (Some("0/a/bc/validhash123.jpg"))
    }
    
    "should allow any uploadHashPathSuffix when loading=true (backwards compatibility)" in {
      val entryDuringLoading = AuditLogEntry(
        siteId = "testsite",
        id = 123, 
        didWhat = AuditLogEntryType.UploadFile,
        doerTrueId = TrueId(234),
        doneAt = new java.util.Date(),
        browserIdData = BrowserIdData("", 0, None, None),
        uploadHashPathSuffix = Some("../../../etc/passwd"),
        isLoading = true  // Validation disabled during loading
      )
      
      // Should not throw exception when loading=true
      entryDuringLoading.uploadHashPathSuffix should be (Some("../../../etc/passwd"))
    }
  }
}