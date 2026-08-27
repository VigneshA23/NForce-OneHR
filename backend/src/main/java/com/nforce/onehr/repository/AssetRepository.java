package com.nforce.onehr.repository;

import com.nforce.onehr.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByStatus(String status);

    long countByStatus(String status);

    List<Asset> findByCategoryIdAndStatus(Integer categoryId, String status);

    boolean existsByAssetTag(String assetTag);

    // Backs OrgService.deleteLocation — assets.location_id is nullable, so a location being
    // permanently deleted just detaches from any assets still pointing at it (preserving the
    // asset records themselves) rather than blocking deletion or deleting inventory data.
    @Modifying
    @Query("UPDATE Asset a SET a.location = NULL WHERE a.location.id = :locationId")
    void clearLocationReferences(@Param("locationId") UUID locationId);
}
