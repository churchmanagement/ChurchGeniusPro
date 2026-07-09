	package com.churchgeniuspro.hibernate;
	
	import jakarta.persistence.Column;
	import jakarta.persistence.Entity;
	import jakarta.persistence.GeneratedValue;
	import jakarta.persistence.GenerationType;
	import jakarta.persistence.Id;
	import jakarta.persistence.PrePersist;
	import jakarta.persistence.SequenceGenerator;
	import jakarta.persistence.Table;
	import lombok.Data;
	
	import java.util.Date;
	
	/**
	 * Represents a purpose category.
	 * Mapped to the {@code purpose} table.
	 */
	@Data
	@Entity
	@Table(name = "purpose")
	public class Purpose {
	
	    @Id
	    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "purpose_seq")
	    @SequenceGenerator(name = "purpose_seq", sequenceName = "purpose_id_seq", allocationSize = 1)
	    @Column(name = "id", nullable = false, updatable = false)
	    private Integer id;
	
	    @Column(name = "purpose_name", nullable = false)
	    private String purposeName;
	
	    /** Optional org identifier from the app_user who created this record. Null for church-level accounts. */
	    @Column(name = "app_client_id")
	    private String appClientId;
	
	    @Column(name = "delete_flag", nullable = false)
	    private boolean deleteFlag;
	
	    @Column(name = "created_date", nullable = false, updatable = false)
	    private Date createdDate;
	
	    @PrePersist
	    protected void onCreate() {
	        this.createdDate = new Date();
	        this.deleteFlag  = false;
	    }
	}
