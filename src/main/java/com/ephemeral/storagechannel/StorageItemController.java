package com.ephemeral.storagechannel;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.StorageItemDto;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class StorageItemController {

    private final StorageItemService items;

    public StorageItemController(StorageItemService items) {
        this.items = items;
    }

    @GetMapping("/api/channels/{channelId}/storage")
    public List<StorageItemDto> list(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                     @RequestParam(required = false) UUID parent) {
        return items.list(user.id(), channelId, parent);
    }

    public record FolderRequest(String name, UUID parentId) {}

    @PostMapping("/api/channels/{channelId}/storage/folders")
    public StorageItemDto createFolder(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                       @RequestBody FolderRequest req) {
        return items.createFolder(user.id(), channelId, req.parentId(), req.name());
    }

    public record FileRequest(UUID attachmentId, UUID parentId, String name) {}

    @PostMapping("/api/channels/{channelId}/storage/files")
    public StorageItemDto addFile(@CurrentUser AuthUser user, @PathVariable UUID channelId,
                                  @RequestBody FileRequest req) {
        return items.addFile(user.id(), channelId, req.parentId(), req.attachmentId(), req.name());
    }

    public record RenameRequest(String name) {}

    @PatchMapping("/api/storage-items/{id}")
    public StorageItemDto rename(@CurrentUser AuthUser user, @PathVariable UUID id,
                                 @RequestBody RenameRequest req) {
        return items.rename(user.id(), id, req.name());
    }

    @DeleteMapping("/api/storage-items/{id}")
    public void delete(@CurrentUser AuthUser user, @PathVariable UUID id) {
        items.delete(user.id(), id);
    }
}
