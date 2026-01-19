/// <reference path="../test-types.ts"/>

import * as _ from 'lodash';
import assert from '../utils/ty-assert';
import server from '../utils/server';
import { buildSite } from '../utils/site-builder';
import { TyE2eTestBrowser } from '../utils/ty-e2e-test-browser';

let brA: TyE2eTestBrowser;
let brB: TyE2eTestBrowser;
let owen: Member;
let owensBrowser: TyE2eTestBrowser;
let maria: Member;
let mariasBrowser: TyE2eTestBrowser;
let michael: Member;
let zelda: Member;
let strangersBrowser: TyE2eTestBrowser;

let siteIdAddress: IdAddress;
let siteId;
let forum: TwoPagesTestForum;

const GroupsFirstFullName = 'GroupsFirstFullName';
const GroupsFirstUsername = 'groups_1st_username';

const GroupsFirstNames = { username: GroupsFirstUsername, fullName: GroupsFirstFullName };


describe("many-users-mention-list-join-group  TyT0326SKDGW2", () => {

  it("import a site", async () => {
    const builder = buildSite();
    forum = builder.addTwoPagesForum({
      title: "Many Users Tests",
      members: undefined, // default = everyone
    });

    // Add 100 members: Minion Mia1, Mia2, Mia3 ... Mia100.
    // They'll have lowercase usernames:  minion_mia1, ...mia2,  ...mia3  and so on.
    builder.addMinions({ oneWordName: "Mia", howMany: 100, mixedCaseUsernameStartWithUpper: false });

    // These will have mixed case usernames:  Minion_Mina1, ...Mina2 etc.
    builder.addMinions({ oneWordName: "Mina", howMany: 150, mixedCaseUsernameStartWithUpper: true });

    // Add Minion Zelda.
    [zelda] = builder.addMinions({ oneWordName: "Zelda", howMany: 1,
        mixedCaseUsernameStartWithUpper: false });

    assert.ok(builder.getSite() === forum.siteData);
    assert.greaterThan(builder.getSite().members.length, 251);
    siteIdAddress = server.importSiteData(forum.siteData);
    siteId = siteIdAddress.id;
  });

  it("initialize people", async () => {
    brA = new TyE2eTestBrowser(wdioBrowserA, 'brA');
    brB = new TyE2eTestBrowser(wdioBrowserB, 'brB');

    owen = forum.members.owen;
    owensBrowser = brA;

    maria = forum.members.maria;
    mariasBrowser = brB;
    michael = forum.members.michael;

    strangersBrowser = brB;
  });

  it("Owen logs in to the groups page", async () => {
    await owensBrowser.groupListPage.goHere(siteIdAddress.origin);
    await owensBrowser.complex.loginWithPasswordViaTopbar(owen);
  });



  // ----- Groups: Adding member, when many users to list  TyT602857SKR


  it("... creates a group to edit", async () => {
    await owensBrowser.groupListPage.createGroup(GroupsFirstNames);
  });

  it("... adds Maria, works fine, she's before all the minions, alphabetically", async () => {
    await owensBrowser.userProfilePage.groupMembers.addOneMember(maria.username);
  });

  it("Owen starts typing Michael", async () => {
    await owensBrowser.userProfilePage.groupMembers.openAddMemberDialog();
    await owensBrowser.addUsersToPageDialog.focusNameInputField();
    await owensBrowser.addUsersToPageDialog.startTypingNewName("Mi");
  });

  it("... sees Michael", async () => {
    await owensBrowser.waitUntilAnyTextMatches('.Select-option', michael.username);
  });

  it("... and many minions", async () => {
    await owensBrowser.waitUntilAnyTextMatches('.Select-option', "minion_mia22");
    await owensBrowser.waitUntilAnyTextMatches('.Select-option', "minion_mia33");
  });

  it("... Michael is listed first", async () => {
    await owensBrowser.waitUntil(async () => {
      const text = await owensBrowser.waitAndGetVisibleText('.Select-option');
      return text.indexOf(michael.username) >= 0;
    }, {
      message: `Michael before the minions?`
    })
  });

  it("... hits Enter to add Michael", async () => {
    await owensBrowser.addUsersToPageDialog.hitEnterToSelectUser();
  });

  it("Owen continuse typing: 'Minion Mia7'", async () => {
    await owensBrowser.addUsersToPageDialog.startTypingNewName("minion_mia7");
  });

  it("... Sees 11 minions", async () => {
    await owensBrowser.waitForAtMost(11, '.Select-option');
    await owensBrowser.waitForAtLeast(11, '.Select-option');
    assert.eq(await owensBrowser.count('.Select-option'), 11);
  });

  it("... adds 'Minion Mia77'", async () => {
    await owensBrowser.addUsersToPageDialog.appendChars("7");
    await owensBrowser.addUsersToPageDialog.hitEnterToSelectUser();
  });

  it("... saves Michael and Minion_Mia77", async () => {
    await owensBrowser.addUsersToPageDialog.submit();
  });

  it("... adds 'Minion Mina134' — with first username letter in Uppercase", async () => {
    await owensBrowser.userProfilePage.groupMembers.openAddMemberDialog();
    await owensBrowser.addUsersToPageDialog.addOneUser('Minion_Mina134');
    await owensBrowser.addUsersToPageDialog.submit();
  });

  it("Owen adds Zelda — she's listed *after* all the minions", async () => {
    await owensBrowser.userProfilePage.groupMembers.addOneMember(zelda.username);
  });

  it("There are now 5 people in the group", async () => {
    assert.eq(await owensBrowser.userProfilePage.groupMembers.getNumMembers(), 5);
  });

  it("... namely Maria, Michael and the minions", async () => {
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent(maria.username);
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent(michael.username);
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent('minion_mia77');
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent('Minion_Mina134');
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent(zelda.username);
  });



  // ----- Admin Area: Listing many users


  it("Owen goes to the admin area, the users list", async () => {
    await owensBrowser.adminArea.goToUsersEnabled();
  });

  it("Oh so many. Owen types ... Maria, Michael, Zelda? Where?", async () => {
    // TESTS_MISSING  TyT60295KTDT
  });



  // ----- Discussions: Mentioning someone, finding via name prefix  TyT2602SKJJ356


  it("Maria logs in", async () => {
    await mariasBrowser.go2(siteIdAddress.origin + '/' + forum.topics.byMichaelCategoryA.slug);
    await mariasBrowser.complex.loginWithPasswordViaTopbar(maria);
  });

  it("Maria starts typing Michael's name:  '@mi...'", async () => {
    await mariasBrowser.topic.clickReplyToOrigPost();
    await mariasBrowser.editor.editText(`Hello @mi`);
  });

  it("... his name appears", async () => {
    await mariasBrowser.waitUntilAnyTextMatches('.rta__entity', michael.username);
  });

  it("... beore all the 'minon...'s", async () => {
    await mariasBrowser.assertNthTextMatches('.rta__entity', 1, michael.username);
  });

  it("... there're > 30 minions", async () => {
    await mariasBrowser.waitForAtLeast(30, '.rta__entity');
  });

  it("Maria clicks Enter to auto-complete Michael's name", async () => {
    await mariasBrowser.keys(['Enter']);
  });

  it("Maria continues typing Zelda", async () => {
    await mariasBrowser.editor.editText(` and @minion_z`, { append: true });
  });

  it("... her name appears", async () => {
    await mariasBrowser.waitUntilAnyTextMatches('.rta__entity', zelda.fullName);
  });

  it("... there's just that single name starting with Z", async () => {
    await mariasBrowser.waitForAtLeast(1, '.rta__entity');
    assert.eq(await mariasBrowser.count('.rta__entity'), 1);
  });

  it("Maria clicks Enter to auto-complete Zelda's name", async () => {
    await mariasBrowser.keys(['Enter']);
  });

  it("Maria mentions one of the Uppercase username minions too: types Minion_Mina103", async () => {
    await mariasBrowser.editor.editText(` and @Minion_Mina103`, { append: true });
  });

  it("... there's only one such minion", async () => {
    await mariasBrowser.waitForAtMost(1, '.rta__entity');
    assert.eq(await mariasBrowser.count('.rta__entity'), 1);
  });

  it("... hits Enter to select @Minion_Mina103", async () => {
    await mariasBrowser.keys(['Enter']);
  });

  it("Maria submits the message", async () => {
    await mariasBrowser.editor.save();
  });

  it("Michael gets notified", async () => {
    await server.waitUntilLastEmailMatches(
        siteIdAddress.id, michael.emailAddress,
        [michael.username, zelda.username, 'Minion_Mina103']);
  });

  it("... and Zelda", async () => {
    await server.waitUntilLastEmailMatches(
        siteIdAddress.id, zelda.emailAddress,
        [michael.username, zelda.username, 'Minion_Mina103']);
  });

  it("... and, last but not least — really not least — Minion_Mina103", async () => {
    await server.waitUntilLastEmailMatches(
        siteIdAddress.id, zelda.emailAddress,
        [michael.username, zelda.username, 'Minion_Mina103']);
  });


});

