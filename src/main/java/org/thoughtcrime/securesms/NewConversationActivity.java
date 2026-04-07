/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import static org.thoughtcrime.securesms.ConversationActivity.CHAT_ID_EXTRA;
import static org.thoughtcrime.securesms.ConversationActivity.TEXT_EXTRA;
import static org.thoughtcrime.securesms.util.ShareUtil.acquireRelayMessageContent;
import static org.thoughtcrime.securesms.util.ShareUtil.isRelayingMessageContent;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import chat.delta.rpc.types.SecurejoinSource;
import chat.delta.rpc.types.SecurejoinUiPath;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import com.b44t.messenger.DcEvent;
import com.google.android.material.badge.BadgeDrawable;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.qr.QrActivity;
import org.thoughtcrime.securesms.qr.QrCodeHandler;

/**
 * Activity container for starting a new conversation.
 *
 * @author Moxie Marlinspike
 */
public class NewConversationActivity extends ContactSelectionActivity
    implements DcEventCenter.DcEventDelegate {

  private static final String TAG = NewConversationActivity.class.getSimpleName();
  private BottomNavigationView bottomNav;

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    bottomNav = findViewById(R.id.bottom_navigation);
    bottomNav.setVisibility(View.VISIBLE);
    bottomNav.setSelectedItemId(R.id.nav_contacts);
    bottomNav.setOnItemSelectedListener(item -> {
      int id = item.getItemId();
      if (id == R.id.nav_chats) {
        Intent intent = new Intent(this, ConversationListActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        overridePendingTransition(0, 0);
        return false;
      } else if (id == R.id.nav_contacts) {
        return true;
      } else if (id == R.id.nav_settings) {
        Intent intent = new Intent(this, ApplicationPreferencesActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        overridePendingTransition(0, 0);
        return false;
      }
      return false;
    });
  }

  @Override
  public void onResume() {
    super.onResume();
    updateChatsTabBadge();
    DcEventCenter eventCenter = DcHelper.getEventCenter(this);
    eventCenter.addObserver(DcContext.DC_EVENT_INCOMING_MSG, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSGS_NOTICED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSGS_CHANGED, this);
  }

  @Override
  public void onPause() {
    super.onPause();
    DcHelper.getEventCenter(this).removeObservers(this);
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    updateChatsTabBadge();
  }

  private void updateChatsTabBadge() {
    int count = DcHelper.getContext(this).getFreshMsgs().length;
    if (count > 0) {
      BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.nav_chats);
      badge.setNumber(count);
      badge.setBackgroundColor(ContextCompat.getColor(this, R.color.unread_count));
    } else {
      bottomNav.removeBadge(R.id.nav_chats);
    }
  }

  @Override
  public void onContactSelected(int contactId) {
    if (contactId == DcContact.DC_CONTACT_ID_NEW_GROUP) {
      startActivity(new Intent(this, GroupCreateActivity.class));
    } else if (contactId == DcContact.DC_CONTACT_ID_NEW_UNENCRYPTED_GROUP) {
      Intent intent = new Intent(this, GroupCreateActivity.class);
      intent.putExtra(GroupCreateActivity.UNENCRYPTED, true);
      startActivity(intent);
    } else if (contactId == DcContact.DC_CONTACT_ID_NEW_BROADCAST) {
      Intent intent = new Intent(this, GroupCreateActivity.class);
      intent.putExtra(GroupCreateActivity.CREATE_BROADCAST, true);
      startActivity(intent);
    } else if (contactId == DcContact.DC_CONTACT_ID_QR_INVITE) {
      new IntentIntegrator(this).setCaptureActivity(QrActivity.class).initiateScan();
    } else {
      final DcContext dcContext = DcHelper.getContext(this);
      if (dcContext.getChatIdByContactId(contactId) != 0) {
        openConversation(dcContext.getChatIdByContactId(contactId));
      } else {
        String name = dcContext.getContact(contactId).getDisplayName();
        new AlertDialog.Builder(this)
            .setMessage(getString(R.string.ask_start_chat_with, name))
            .setCancelable(true)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                android.R.string.ok,
                (dialog, which) -> {
                  openConversation(dcContext.createChatByContactId(contactId));
                })
            .show();
      }
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK) return;

    switch (requestCode) {
      case IntentIntegrator.REQUEST_CODE:
        IntentResult scanResult = IntentIntegrator.parseActivityResult(resultCode, data);
        QrCodeHandler qrCodeHandler = new QrCodeHandler(this);
        qrCodeHandler.handleOnlySecureJoinQr(
            scanResult.getContents(), SecurejoinSource.Scan, SecurejoinUiPath.NewContact);
        break;
      default:
        break;
    }
  }

  private void openConversation(int chatId) {
    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(TEXT_EXTRA, getIntent().getStringExtra(TEXT_EXTRA));
    intent.setDataAndType(getIntent().getData(), getIntent().getType());

    intent.putExtra(CHAT_ID_EXTRA, chatId);
    if (isRelayingMessageContent(this)) {
      acquireRelayMessageContent(this, intent);
    }
    startActivity(intent);
    finish();
  }
}
