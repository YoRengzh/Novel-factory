package cn.novel.yonren.trigger.http;

import cn.novel.yonren.api.dto.AssetContentDTO;
import cn.novel.yonren.api.dto.StyleViewDTO;
import cn.novel.yonren.api.dto.UserReferenceDTO;
import cn.novel.yonren.domain.novel.service.armory.prompt.UserAssetStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 用户自备资产端点：设置页「我的参考资料」与「文风」读写。
 * 资料/文风保存后即时生效——下次作业的资料索引与文风装配直接用新内容（无需重启）
 */
@Slf4j
@RestController
@RequestMapping("/api/assets")
public class UserAssetController {

    @Resource
    private UserAssetStore userAssetStore;

    // ===== 我的参考资料 =====

    @GetMapping("/references")
    public List<UserReferenceDTO> listReferences() {
        return userAssetStore.listReferences();
    }

    @GetMapping("/references/{fileName}")
    public AssetContentDTO readReference(@PathVariable("fileName") String fileName) {
        try {
            return new AssetContentDTO(userAssetStore.readReference(fileName), null, null);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/references/{fileName}")
    public List<UserReferenceDTO> writeReference(@PathVariable("fileName") String fileName,
                                                 @RequestBody AssetContentDTO body) {
        try {
            userAssetStore.writeReference(fileName, body == null ? null : body.getContent());
            return userAssetStore.listReferences();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("用户参考资料保存失败：{}", fileName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** 从本地文件导入资料（复制全文，源文件之后移动/删除不受影响） */
    @org.springframework.web.bind.annotation.PostMapping("/references/{fileName}/import")
    public List<UserReferenceDTO> importReference(@PathVariable("fileName") String fileName,
                                                  @RequestBody cn.novel.yonren.api.dto.ReferenceImportRequestDTO body) {
        try {
            String sourcePath = body == null ? null : body.getSourcePath();
            userAssetStore.importReference(fileName, sourcePath == null ? null : java.nio.file.Path.of(sourcePath));
            return userAssetStore.listReferences();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("用户参考资料导入失败：{} <- {}", fileName, body == null ? "?" : body.getSourcePath(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/references/{fileName}")
    public List<UserReferenceDTO> deleteReference(@PathVariable("fileName") String fileName) {
        try {
            userAssetStore.deleteReference(fileName);
            return userAssetStore.listReferences();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("用户参考资料删除失败：{}", fileName, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ===== 文风 =====

    @GetMapping("/styles")
    public List<StyleViewDTO> listStyles() {
        return userAssetStore.listStyles();
    }

    @GetMapping("/styles/{code}")
    public AssetContentDTO readStyle(@PathVariable("code") String code) {
        try {
            UserAssetStore.StyleContent content = userAssetStore.readStyle(code);
            return new AssetContentDTO(content.content(), content.overridden(), content.builtin());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PutMapping("/styles/{code}")
    public List<StyleViewDTO> writeStyle(@PathVariable("code") String code,
                                         @RequestBody AssetContentDTO body) {
        try {
            userAssetStore.writeStyle(code, body == null ? null : body.getContent());
            return userAssetStore.listStyles();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.error("文风保存失败：{}", code, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @DeleteMapping("/styles/{code}")
    public List<StyleViewDTO> deleteStyle(@PathVariable("code") String code) {
        try {
            userAssetStore.deleteStyle(code);
            return userAssetStore.listStyles();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("文风删除失败：{}", code, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
