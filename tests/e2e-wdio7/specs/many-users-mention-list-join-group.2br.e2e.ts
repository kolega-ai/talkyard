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


describe("many-users-mention-list-join-group.2br  TyT0326SKDGW2", () => {

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
    await owensBrowser.addUsersToPageDialog.waitUntilMatchingUsersListed();
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
    await owensBrowser.waitUntil(async () => {
      const num = await owensBrowser.userProfilePage.groupMembers.getNumMembers();
      return num == 5;
    }, {
      message: `Waiting for 5 members`,
      refreshBetween: true,
    });
  });

  it("... namely Maria, Michael and the minions", async () => {
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent(maria.username);
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent(michael.username);
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent('minion_mia77');
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent('Minion_Mina134');
    await owensBrowser.userProfilePage.groupMembers.waitUntilMemberPresent(zelda.username);
  });



  // ----- Admin Area: Listing many users


  let expectedUsernames: St[];

  it("Owen goes to the admin area, the users list", async () => {
    await owensBrowser.adminArea.goToUsersEnabled();
  });

  it("... Owen sees Zelda, and Minion_Mina 150...102, that's 50 in total [limit_50]", async () => {
    expectedUsernames = ['minion_zelda'];
    for (let nr = 150; nr >= 102; nr--) {
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    await owensBrowser.adminArea.users.waitForNumUsers(50);
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it("... Owen loads more users: Minion_Mina 101...52", async () => {
    for (let nr = 101; nr >= 52; nr--) {
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 2 });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it("... Owen loads more users: Minion_Mina 51...2", async () => {
    for (let nr = 51; nr >= 2; nr--) {
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 3 });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... Owen loads Minion_Mina 1,  and minion_mia 100..52
                                      (note: lowercase, and 'mia' not 'mina')`, async () => {
    expectedUsernames.push(`Minion_Mina1`)
    for (let nr = 100; nr >= 52; nr--) {
      expectedUsernames.push(`minion_mia${nr}`)  // Mia not Mina
    }
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 4 });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... Owen loads minion_mia 51...2`, async () => {
    for (let nr = 51; nr >= 2; nr--) {
      expectedUsernames.push(`minion_mia${nr}`)
    }
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 5 });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... Owen loads minion_mia 1, plus the usual suspects — 260 in total  TyT60295KTDT`, async () => {
    expectedUsernames.push(`minion_mia1`)
    expectedUsernames.push('mallory');
    expectedUsernames.push('michael');
    expectedUsernames.push('maria');
    expectedUsernames.push('memah');
    expectedUsernames.push('regina');
    expectedUsernames.push('Corax');
    expectedUsernames.push('mod_modya');
    expectedUsernames.push('mod_mons');
    expectedUsernames.push('owen_owner');
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 5 + 10 });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... that's all, Load-more button gone`, async () => {
    assert.that(await owensBrowser.adminArea.users.hasLoadedAll());
    assert.not(await owensBrowser.adminArea.users.canLoadMore());
  });


  // ----- Staff user list filters   TyTSTAUSRLSFIL

  // Username filter

  it(`Owen toggles filters on`, async () => {
    await owensBrowser.adminArea.users.setShowFilters(true);
  });

  it(`... types 'Mina' as username filter`, async () => {
    await owensBrowser.adminArea.users.setUsernameFilter("Mina");
  });

  it(`... sees the last 50 Minion_Mina only`, async () => {
    expectedUsernames = [];
    for (let nr = 150; nr >= 101; nr--) {
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    await owensBrowser.adminArea.users.waitForNumUsers(50);
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... Owen loads 50 more users, sees Mina 100...51`, async () => {
    for (let nr = 100; nr >= 51; nr--) {
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 2 });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... Owen loads 50 more users, sees Mina 50...1`, async () => {
    for (let nr = 50; nr >= 1; nr--) {
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 3 });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... Owen tries to load 50 more users, but there are no more`, async () => {
    await owensBrowser.adminArea.users.loadMoreUsers({ waitForNum: 50 * 3, waitForAll: true });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames); // unchanged
  });


  // Email filter

  it(`Owen types '5' as email filter`, async () => {
    await owensBrowser.adminArea.users.setEmailFilter("5");
  });

  it(`... sees Minion_Mina 150, 145, 135, ... 55, 50, 45, ... 15, 5 only`, async () => {
    expectedUsernames = [];
    expectedUsernames.push(`Minion_Mina150`);    // 1 +
    for (let nr = 145; nr >= 65; nr -= 10) {     // 9 +
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    for (let nr = 59; nr >= 50; nr -= 1) {       // 10 +
      expectedUsernames.push(`Minion_Mina${nr}`)
    }
    expectedUsernames.push(`Minion_Mina45`);     // 5  =  25
    expectedUsernames.push(`Minion_Mina35`);
    expectedUsernames.push(`Minion_Mina25`);
    expectedUsernames.push(`Minion_Mina15`);
    expectedUsernames.push(`Minion_Mina5`);
    await owensBrowser.adminArea.users.waitForNumUsers({ waitForNum: 25, waitForAll: true });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... there's no Load More button`, async () => {
    assert.that(await owensBrowser.adminArea.users.hasLoadedAll());
    assert.not(await owensBrowser.adminArea.users.canLoadMore());
  });

  // Name filter

  it(`Owen types '0' as name filter`, async () => {
    await owensBrowser.adminArea.users.setFullNameFilter("0");
  });

  it(`... sees Minion_Mina 150, 105 and 50 only. Will he give them a secret mission?`, async () => {
    expectedUsernames = [];
    expectedUsernames.push(`Minion_Mina150`);
    expectedUsernames.push(`Minion_Mina105`);
    expectedUsernames.push(`Minion_Mina50`);
    await owensBrowser.adminArea.users.waitForNumUsers({ waitForNum: 3, waitForAll: true });
    await owensBrowser.adminArea.users.assertUsenamesAreAndOrder(expectedUsernames);
  });

  it(`... no Load More button`, async () => {
    assert.that(await owensBrowser.adminArea.users.hasLoadedAll());
    assert.not(await owensBrowser.adminArea.users.canLoadMore());
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

