declare module 'react-simple-maps' {
  import type { ComponentType, ReactNode, SVGProps } from 'react'

  export interface ComposableMapProps {
    projection?: string
    projectionConfig?: { scale?: number; center?: [number, number] }
    style?: Record<string, string>
    children?: ReactNode
  }
  export const ComposableMap: ComponentType<ComposableMapProps>

  export interface GeographiesProps {
    geography: string
    children: (data: { geographies: any[] }) => ReactNode
  }
  export const Geographies: ComponentType<GeographiesProps>

  export interface GeographyProps {
    geography: any
    fill?: string
    stroke?: string
    strokeWidth?: number
    style?: Record<string, any>
    key?: string | number
  }
  export const Geography: ComponentType<GeographyProps>

  export interface MarkerProps {
    coordinates: [number, number]
    children?: ReactNode
  }
  export const Marker: ComponentType<MarkerProps>
}
