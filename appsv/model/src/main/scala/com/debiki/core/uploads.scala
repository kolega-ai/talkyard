/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
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


/** An uploaded file is located at the baseUrlHostAndPath + hashPath,
  * e.g.  some-cdn.com/some/path/0/x/yz/wq...abc.jpg
  * where xyzwq...abc (note: no slashes) is the file's hash (sha-256, base32, truncated
  * to 33 chars). '0/x/yz/wq...abc' is the file's "hash path" — because it's a hash, with
  * slashes inserted so that it's a path — this avoids us placing all files in the exact
  * same directory. Some file system don't want super many files in just one directory.
  * The digit (0 in the example above) indicates the file size, see UploadsDao.sizeKiloBase4.
  *
  * @param baseUrl e.g. '/-/u/'
  * @param hashPath e.g. '1/o/cy/wddssa4xpzugiaego7seuyurxvgef5.jpg'
  */
case class UploadRef(
  // CLEAN_UP remove baseUrl [2KGLCQ4] ?  what did I think? ?? ...
  // Maybe some "weird"? CDNs require serving uploads from "weird" paths instead of /-/u/(site-id)/... ?
  baseUrl: String, hashPath: String) {

  def url: String = baseUrl + hashPath

  // def isApproved: Boolean  [is_upl_ref_aprvd] ?

}


object UploadRef {

  /** Not too much, not too little? And, ChatGPT says that:
   * "ext4, NTFS, APFS all use 255 bytes per filename".
   */
  val MaxUploadedFileNameLen = 255
}


case class UploadInfo(sizeBytes: Int, mimeType: String, numReferences: Int)

case class UploadInfoVb(
  baseUrl: St,
  hashPath: St,
  sizeBytes: i32,
  mimeType: St,
  //numReferences: i32, — don't expose
  uploadedFileName: Opt[St],  // oops, the [uploaded_file_name] is forgotten, always None
  width: Opt[i32],
  height: Opt[i32],
  postId: Opt[PostId],
  patId: Opt[PatId],
  addedById: PatId,
  addedAt: When,

  // Currently loading Post:s and PageStuff:s separately. Maybe could load in the same
  // SQL query (and join w those tables too), if that turns out to be faster?
  // But seems there'd be a bit many  _another_join  then!?
  // Anyway, then could include here in UploadInfoVb:
  //
  // Post tags? — _another_join and [array_agg]
  // Category? — _another_join
  //
  // pageId: Opt[St],
  // pageTitle? — but it's in posts3, _another_join?
  // postNr: Opt[PostNr],
  // postCreatedAt: Opt[When],
  // postCreatedById: Opt[PatId],
  // postAapprovedSource: Opt[St],
  // postAapprovedHtmlSanitized: Opt[St],
  // po.pinned_position,
  // po.deleted_status,
  // po.closed_status,
  // po.hidden_at,
  // po.num_pending_flags,
  // po.num_handled_flags,
  // po.num_like_votes,
  // po.num_wrong_votes,
  // po.num_times_read,
  // po.num_bury_votes,
  // po.num_unwanted_votes,
  // po.type
  )


// Later? Info of interest to server admins? Which isn't incl in UploadInfoVb above
// (so won't accidentally expose to single site admins).
//
// case class SrvUploadInfoVb(...)
