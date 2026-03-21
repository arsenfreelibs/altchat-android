package org.thoughtcrime.securesms.altplatform.search;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.altplatform.network.dto.UserProfileResponse;

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
        holder.nameLabel.setText(profile.displayName());
        holder.usernameLabel.setText("@" + profile.username);
        holder.addrLabel.setText(profile.primaryAddr());
        holder.itemView.setOnClickListener(v -> listener.onUserClick(profile));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameLabel;
        TextView usernameLabel;
        TextView addrLabel;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            nameLabel = itemView.findViewById(R.id.name_label);
            usernameLabel = itemView.findViewById(R.id.username_label);
            addrLabel = itemView.findViewById(R.id.addr_label);
        }
    }
}
