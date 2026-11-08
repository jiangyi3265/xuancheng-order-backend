CREATE TABLE IF NOT EXISTS jiedan_webpush_key (
  id tinyint PRIMARY KEY,
  public_key varchar(100) NOT NULL,
  private_key varchar(60) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS jiedan_webpush_device (
  endpoint_hash char(64) PRIMARY KEY,
  member_id bigint NOT NULL,
  binding char(36) NOT NULL,
  endpoint varchar(2048) NOT NULL,
  p256dh varchar(100) NOT NULL,
  auth_key varchar(32) NOT NULL,
  KEY member_devices(member_id)
) ENGINE=InnoDB;
