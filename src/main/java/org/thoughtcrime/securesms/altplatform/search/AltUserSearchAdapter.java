package org.thoughtcrime.securesms.altplatform.search;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.altplatform.network.dto.UserProfileResponse;
import org.thoughtcrime.securesms.components.AvatarView;
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto;

import java.util.ArrayList;
import java.util.List;

public class AltUserSearchAdapter extends RecyclerView.Adapter<AltUserSearchAdapter.ViewHolder> {

    public interface OnUserClickListener {
        void onUserClick(UserProfileResponse profile);
    }

    private final List<UserProfileResponse> items = new ArrayList<>();
    private final OnUserClickListener listener;

    public AltUserSearchAdapter(OnUserClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<UserProfileResponse> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alt_user_search, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        UserProfileResponse profile = items.get(position);
        String displayName = profile.displayName();
        holder.nameLabel.setText(displayName);
        String uname = profile.username != null && !profile.username.isEmpty() ? "@" + profile.username : "";
        holder.usernameLabel.setText(uname);
        holder.usernameLabel.setVisibility(uname.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        int color = avatarColorForString(profile.username != null ? profile.username : displayName);
        holder.avatar.setImageDrawable(
                new GeneratedContactPhoto(displayName).asDrawable(holder.itemView.getContext(), color));
        holder.itemView.setOnClickListener(v -> listener.onUserClick(profile));
    }

    private static int avatarColorForString(String s) {
        // Same palette DC core uses for contact colors
        int[] palette = {
            0xFF3498DB, 0xFF2ECC71, 0xFFE74C3C, 0xFF9B59B6, 0xFFF39C12,
            0xFF1ABC9C, 0xFFE67E22, 0xFF2980B9, 0xFF27AE60, 0xFF8E44AD
        };
        int hash = s.hashCode();
        return palette[Math.abs(hash) % palette.length];
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        AvatarView avatar;
        TextView nameLabel;
        TextView usernameLabel;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            nameLabel = itemView.findViewById(R.id.name_label);
            usernameLabel = itemView.findViewById(R.id.username_label);
        }
    }
}
