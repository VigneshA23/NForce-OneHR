package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByStatus(String status);

    long countByStatus(String status);

    List<Asset> findByCategoryIdAndStatus(Integer categoryId, String status);

    boolean existsByAssetTag(String assetTag);
}
