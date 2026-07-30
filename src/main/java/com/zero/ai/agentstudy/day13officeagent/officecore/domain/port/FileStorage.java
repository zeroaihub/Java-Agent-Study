package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.context.TenantContext;

import java.util.Optional;

/**
 * 文件存储端口（FileStorage）——出站端口。
 *
 * <p>渲染产物（.docx/.pdf/.pptx）需要落盘或上云。此端口抽象存储读写，具体实现可以是本地文件系统、
 * MinIO、S3、OSS 等。所有操作都带 {@link TenantContext}，由适配器据此做<b>多租户物理隔离</b>
 * （分桶/分目录），从而在同一套服务里安全承载多个企业客户的数据。</p>
 *
 * @author zero
 */
public interface FileStorage {

    /**
     * 保存一个文件对象。
     *
     * @param tenant    租户上下文，用于隔离
     * @param objectKey 对象键（相对路径/键名）
     * @param mediaType MIME 类型
     * @param content   文件字节
     * @return 可访问该文件的引用
     */
    StoredFile save(TenantContext tenant, String objectKey, String mediaType, byte[] content);

    /**
     * 读取一个文件对象。
     *
     * @param tenant    租户上下文
     * @param objectKey 对象键
     * @return 文件字节（不存在则为空）
     */
    Optional<byte[]> load(TenantContext tenant, String objectKey);

    /**
     * 删除一个文件对象。
     *
     * @param tenant    租户上下文
     * @param objectKey 对象键
     * @return 是否删除成功
     */
    boolean delete(TenantContext tenant, String objectKey);

    /**
     * 已存储文件引用值对象。
     *
     * @param objectKey 对象键
     * @param uri       可访问的 URI（如 https / s3 链接）
     * @param size      字节大小
     * @author zero
     */
    record StoredFile(String objectKey, String uri, long size) {

        public StoredFile {
            objectKey = objectKey == null ? "" : objectKey;
            uri = uri == null ? "" : uri;
            size = Math.max(0, size);
        }
    }
}