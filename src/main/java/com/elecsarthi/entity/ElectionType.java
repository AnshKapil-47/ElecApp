package com.elecsarthi.entity;

import com.elecsarthi.enums.ElectionTypeCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "election_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElectionType {

    @Id
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, unique = true)
    private ElectionTypeCode code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "booth_column", nullable = false)
    private String boothColumn;

    @Column(name = "guard_column")
    private String guardColumn;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}
