package com.elecsarthi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "voter")
@IdClass(VoterId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Voter {

    @Id
    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "source_record_key_hash", nullable = false, columnDefinition = "char(64)")
    private String sourceRecordKeyHash;

    @Column(name = "state")
    private String state;

    @Column(name = "district")
    private String district;

    @Column(name = "pc_no")
    private Integer pcNo;

    @Column(name = "pc_name_en")
    private String pcNameEn;

    @Column(name = "pc_name_mt")
    private String pcNameMt;

    @Column(name = "ac_no")
    private Integer acNo;

    @Column(name = "ac_name_en")
    private String acNameEn;

    @Column(name = "ac_name_mt")
    private String acNameMt;

    @Column(name = "part_no")
    private Integer partNo;

    @Column(name = "booth_no")
    private Integer boothNo;

    @Column(name = "booth_name_en")
    private String boothNameEn;

    @Column(name = "booth_name_mt")
    private String boothNameMt;

    @Column(name = "booth_address_en")
    private String boothAddressEn;

    @Column(name = "booth_address_mt")
    private String boothAddressMt;

    @Column(name = "serial_no")
    private Integer serialNo;

    @Column(name = "voter_id")
    private String voterId;

    @Column(name = "v_fname_en")
    private String vFnameEn;

    @Column(name = "v_lname_en")
    private String vLnameEn;

    @Column(name = "v_fname_mt")
    private String vFnameMt;

    @Column(name = "v_lname_mt")
    private String vLnameMt;

    @Column(name = "vr_fname_en")
    private String vrFnameEn;

    @Column(name = "vr_lname_en")
    private String vrLnameEn;

    @Column(name = "vr_fname_mt")
    private String vrFnameMt;

    @Column(name = "vr_lname_mt")
    private String vrLnameMt;

    @Column(name = "house_no")
    private String houseNo;

    @Column(name = "house_location_en")
    private String houseLocationEn;

    @Column(name = "house_location_mt")
    private String houseLocationMt;

    @Column(name = "section_no")
    private Integer sectionNo;

    @Column(name = "section_name_en")
    private String sectionNameEn;

    @Column(name = "section_name_mt")
    private String sectionNameMt;

    @Column(name = "age")
    private Integer age;

    @Column(name = "sex")
    private String sex;

    @Column(name = "rural_urban")
    private String ruralUrban;

    @Column(name = "tehsil")
    private String tehsil;

    @Column(name = "block")
    private String block;

    @Column(name = "pincode")
    private String pincode;

    @Column(name = "municipal_name_nagar_nigam_en")
    private String municipalNameNagarNigamEn;

    @Column(name = "municipal_name_nagar_nigam_mt")
    private String municipalNameNagarNigamMt;

    @Column(name = "nagar_nigam_ward_no")
    private Integer nagarNigamWardNo;

    @Column(name = "municipal_name_nagar_palika_en")
    private String municipalNameNagarPalikaEn;

    @Column(name = "municipal_name_nagar_palika_mt")
    private String municipalNameNagarPalikaMt;

    @Column(name = "nagar_palika_ward_no")
    private Integer nagarPalikaWardNo;

    @Column(name = "municipal_name_nagar_parishad_en")
    private String municipalNameNagarParishadEn;

    @Column(name = "municipal_name_nagar_parishad_mt")
    private String municipalNameNagarParishadMt;

    @Column(name = "nagar_parishad_ward_no")
    private Integer nagarParishadWardNo;

    @Column(name = "zilla_parishad_name_en")
    private String zillaParishadNameEn;

    @Column(name = "zilla_parishad_name_mt")
    private String zillaParishadNameMt;

    @Column(name = "zilla_parishad_name_ward_no")
    private Integer zillaParishadNameWardNo;

    @Column(name = "panchayat_samiti_name_en")
    private String panchayatSamitiNameEn;

    @Column(name = "panchayat_samiti_name_mt")
    private String panchayatSamitiNameMt;

    @Column(name = "panchayat_samiti_name_ward_no")
    private Integer panchayatSamitiNameWardNo;

    @Column(name = "panchayat_name_en")
    private String panchayatNameEn;

    @Column(name = "panchayat_name_mt")
    private String panchayatNameMt;

    @Column(name = "panchayat_no")
    private Integer panchayatNo;

    @Column(name = "mobile_no_enc")
    private byte[] mobileNoEnc;

    @Column(name = "mobile_key_version")
    private Short mobileKeyVersion;

    @Column(name = "voter_image_ref")
    private String voterImageRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
