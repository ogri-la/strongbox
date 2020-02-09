SELECT
  catalogue.*,
  (
    SELECT
      group_concat(name separator '|') 
    FROM
      category 
      JOIN
        addon_category ac 
        ON category.id = ac.category_id 
    WHERE
      ac.addon_source_id = catalogue.source_id 
  )
  AS category_list 
FROM
  catalogue
