/*
 * Copyright (c) 2016 Kaj Magnus Lindberg
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

/// <reference path="../utils/PatternInput.more.ts" />
/// <reference path="../util/stupid-dialog.more.ts" />
/// <reference path="../more-prelude.more.ts" />

//------------------------------------------------------------------------------
   namespace debiki2.pagedialogs {
//------------------------------------------------------------------------------

const r = ReactDOMFactories;
const ModalHeader = rb.ModalHeader;
const ModalTitle = rb.ModalTitle;
const ModalBody = rb.ModalBody;
const ModalFooter = rb.ModalFooter;
const PatternInput = utils.PatternInput;

let movePostsDialog;


export function openMovePostsDialog(store: Store, post: Post, closeCaller, at: Rect) {
  if (!movePostsDialog) {
    movePostsDialog = ReactDOM.render(MovePostsDialog(), utils.makeMountNode());
  }
  movePostsDialog.open(store, post, closeCaller, at);
}

interface MovePostsDiagState {
  store?: Store
  isOpen?: Bo
  ok?: Bo
  atRect?: Rect
  windowWidth?: Nr
  closeCaller?: () => Vo
  post?: Post
  // COULD RENAME from 'new...' to 'other...', to constrast with a newly created page id?
  // 'otherPageId' = moving to other already existing page.  'newPageId' = created a new page.
  newHost?: St
  newPageId?: PageId
  newParentNr?: PostNr
  newParentUrl?: St
  createNewPage?: Bo
}

const MovePostsDialog = createComponent({
  getInitialState: function () {
    return {};
  },

  componentWillUnmount: function() {
    this.isGone = true;
  },

  open: function(store: Store, post: Post, closeCaller, at) {
    this.setState({
      isOpen: true,
      store: store,
      post: post,
      newParentUrl: '',
      closeCaller: closeCaller,
      atRect: at,
      windowWidth: window.innerWidth,
    } satisfies MovePostsDiagState);
  },

  close: function() {
    this.setState({ isOpen: false, store: null, post: null } satisfies MovePostsDiagState);
  },

  moveToOtherSection: function() {
    const state: MovePostsDiagState = this.state;
    const store: Store = state.store;
    const post: Post = state.post;
    const otherSectionType =
        post.postType === PostType.BottomComment ? PostType.Normal : PostType.BottomComment;
    Server.changePostType(post.nr, otherSectionType, () => {
      util.openDefaultStupidDialog({
        onCloseOk: this.close,
        body: r.div({},
          "Moved. ",
          LinkUnstyled({ to: `/-${store.currentPageId}#post-${post.nr}`,
                // We're already on the destination page, so LinkUnstyled does nothing `onClick`,
                // unless: [_same_or_other_pg] (but still nice w link, can right click & copy url)
                onClick: () => {
                  utils.scrollIntoViewInPageColumn(
                        `#post-${post.nr}`, { marginTop: 150, marginBottom: 300 });
                  this.close();
                }
              },
              "Click here to view"))
      });
    });
  },

  moveToNewPage: function() {
    const state: MovePostsDiagState = this.state;
    const post: Post = state.post;
    Server.movePost(post.uniqueId, state.newHost, null, // state.newPageId,
        null /*state.newParentNr*/, true /*createNewPage*/, this.showResult);
  },

  doMove: function() {
    const state: MovePostsDiagState = this.state;
    const post: Post = state.post;
    Server.movePost(post.uniqueId, state.newHost, state.newPageId,
        state.newParentNr, false /*createNewPage*/, this.showResult);
  },

  showResult: function(postAfter: Post, anyNewPageId: PageId | U) {
    if (this.isGone) return;
    const state: MovePostsDiagState = this.state;
    const store: Store = state.store;
    const newPageId = anyNewPageId || state.newPageId;

    if (store.currentPageId === newPageId) {
      // Post moved within the same page. Then scroll to the new location.
      // UX COULD add Back nav entry, which navigates back to the former parent or any
      // sibling just above.
      ReactActions.scrollAndShowPost(postAfter);
    }
    else {
      // Let the user decide him/herself if s/he wants to open a new page.
      const newPostUrl = '/-' + newPageId + '#post-' + postAfter.nr;
      let closeFn: () => V;
      util.openDefaultStupidDialog({
          withCloseFn: fn => closeFn = fn,
          onCloseOk: this.close,
          body: r.div({},
            // This link is to another page, so it works. [_same_or_other_pg]
            "Moved. ", LinkUnstyled({ to: newPostUrl, afterClick: () => { closeFn(); }},
                "Click here to view it."))
          });
    }

    if (state.closeCaller) {
      state.closeCaller();
    }
    this.close();
  },

  previewNewParent: function() {
    const state: MovePostsDiagState = this.state;
    window.open(state.newParentUrl);
  },

  render: function () {
    // Tests:
    //   - See /^ *move post TyTMOPO/ in tests-map.txt.

    let content: RElm | U;
    const state: MovePostsDiagState = this.state;

    if (state.isOpen) {
      const post: Post = state.post;
      const isTopLevelReply = post.parentNr === BodyNr;
      const isChat = post.postType === PostType.ChatMessage;

      const settings: SettingsVisibleClientSide = state.store.settings;
      const showMoveToOtherSection = post_isReply(post) && isTopLevelReply &&
                (settings.progressLayout === ProgressLayout.Enabled
                    // For changing an accidenal Progress Note to a normal discussion reply.
                    || post.postType === PostType.Flat
                    || post.postType === PostType.BottomComment);

      const showMoveToNewPage = post_isReply(post); // [can_mv_post]
      const otherSection = post.postType === PostType.BottomComment ? "discussion" : "progress";

      const postPathRegex =
          //  scheme       host        page id           any slug         post nr
          /^((https?:\/\/)?([^/]+))?\/-([a-zA-Z0-9_]+)(\/[a-zA-Z0-9-_]+)?(#post-([0-9]+))?$/;

      // I18N: The Move Post dialog. Not only for admins, but also mods, and advanced users
      // moving their own comments?

      const moveToNewPageElm = !showMoveToNewPage ? null :
          r.div({ className: 'c_MPD_NwPg' },
              r.h5({}, "Move to new page?"),  // _first_title
              PrimaryButton({ onClick: () => this.moveToNewPage() },
                `Create page, and move comment`),
              r.p({}, "This creates a new page, with this comment as the first post. " + (
                  // Chat messages have no replies [chat_replies] — we use @mentions instead,
                  // for now at least.
                  isChat ? '' : "Replies to this comment get moved too.")));

      content = r.div({},
          moveToNewPageElm,

          !moveToNewPageElm || !showMoveToOtherSection ? null : r.hr(),

          !showMoveToOtherSection ? null : r.div({ className: 's_MPD_OtrSct' },
            PrimaryButton({ onClick: () => this.moveToOtherSection() },
              `Move to ${otherSection} section`),
            r.span({}, " on this page"),
            r.p({}, "This moves any replies to that other section, too.")),

          !moveToNewPageElm && !showMoveToOtherSection ? null : r.hr(),

          r.div({},
          r.h5({}, "Move under another comment?"),
          r.p({}, `Move this comment ${isChat ? '' : "and its replies"
                      } to another comment.`),
          PatternInput({ type: 'text',
            required: false,
            label: "New parent comment URL (can be on another page):", id: 'te_MvPI',
            help: r.span({}, "Tip: Click the ", r.span({ className: 'icon-link' }), " link " +
                "below the destination comment to copy its URL."),
            onChangeValueOk: (value, ok) => {
              const matches = value.match(postPathRegex);
              if (!matches) {
                this.setState({ ok: false } satisfies MovePostsDiagState);
                return;
              }
              this.setState({
                newParentUrl: value,
                newHost: matches[3],
                newPageId: matches[4],
                newParentNr: parseInt(matches[7]), // might be null —> the orig post, BodyNr
                ok: ok
              } satisfies MovePostsDiagState);
            },
            regex: postPathRegex,
            message: rFr({}, "Invalid parent comment URL, should be like: ", r.kbd({}, location.origin +
                "/-[page_id]#post-[post_nr]"))}),
          PrimaryButton({ onClick: this.doMove, disabled: !state.ok || !state.newParentUrl,
                className: 'e_MvPB' },
            "Move"),
          Button({ onClick: this.previewNewParent }, "Preview")));
    }

    return (
      utils.DropdownModal({ show: state.isOpen, onHide: this.close, showCloseButton: true,
          atRect: state.atRect, windowWidth: state.windowWidth,
          dialogClassName2: 's_MvPD'   },
        ModalHeader({}, ModalTitle({}, "Move comment")),
        ModalBody({}, content),
        ModalFooter({}, Button({ onClick: this.close }, "Cancel"))));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
