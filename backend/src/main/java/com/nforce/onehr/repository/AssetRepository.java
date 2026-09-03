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

    // Backs AssetService#listAllAssets — fetches category and location alongside the assets in
    // one query instead of one lazy-load per row per association (2 extra round trips per asset
    // otherwise). LEFT JOIN for location since it's nullable; category is NOT NULL so an inner
    // join is safe and won't drop any rows.
    @Query("SELECT a FROM Asset a JOIN FETCH a.category LEFT JOIN FETCH a.location")
    List<Asset> findAllWithDetails();

    // Backs OrgService.deleteLocation — assets.location_id is nullable, so a location being
    // permanently deleted just detaches from any assets still pointing at it (preserving the
    // asset records themselves) rather than blocking deletion or deleting inventory data.
    @Modifying
    @Query("UPDATE Asset a SET a.location = NULL WHERE a.location.id = :locationId")
    void clearLocationReferences(@Param("locationId") UUID locationId);
}
