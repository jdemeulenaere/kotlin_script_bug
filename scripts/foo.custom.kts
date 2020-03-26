// Uncomment the import and println to make evaluation fail.

@file:Import("bar.custom.kts")
// @file:Import("sub_folder/foo.custom.kts")

println("bar.custom.kts says: ${fromBar}")
// println("sub_folder/foo.custom.kts says: ${fromSubFolder}")